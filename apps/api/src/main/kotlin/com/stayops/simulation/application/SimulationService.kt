package com.stayops.simulation.application

import com.stayops.identity.Actor
import com.stayops.shared.command.CommandContext
import com.stayops.shared.error.ErrorCode
import com.stayops.shared.error.NotFoundException
import com.stayops.shared.error.StayOpsException
import com.stayops.simulation.engine.DispatchRule
import com.stayops.simulation.engine.SimDataset
import com.stayops.simulation.engine.SimMetrics
import com.stayops.simulation.engine.SimParameters
import com.stayops.simulation.engine.SimPolicy
import com.stayops.simulation.engine.SimulationDefinitions
import com.stayops.simulation.engine.SimulationEngine
import com.stayops.simulation.infrastructure.SimulationDatasetEntity
import com.stayops.simulation.infrastructure.SimulationDatasetRepository
import com.stayops.simulation.infrastructure.SimulationRunEntity
import com.stayops.simulation.infrastructure.SimulationRunRepository
import com.stayops.staff.domain.StaffRole
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 시뮬레이션 (docs/v4.1/02 §2.2·§9, 01 §5 H3). 엔진은 Kotlin, 데이터셋은 Python 생성기 출력 파일을 import 한다.
 * 같은 데이터셋·정책·파라미터·엔진 버전이면 같은 결과다. 결과는 합성 데이터에 대한 상대 비교이며 운영 성과가 아니다.
 */
@Service
class SimulationService(
    private val datasets: SimulationDatasetRepository,
    private val runs: SimulationRunRepository,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {
    // ------------------------------------------------------------- dataset

    @Transactional(propagation = Propagation.MANDATORY)
    fun importDataset(ctx: CommandContext, propertyId: UUID, payload: Map<String, Any?>): DatasetView {
        ctx.actor.requireProperty(propertyId)
        ctx.actor.requireRole(StaffRole.MANAGER)
        val parsed = runCatching { jsonMapper.convertValue(payload, SimDataset::class.java) }.getOrElse {
            throw StayOpsException(ErrorCode.VALIDATION_ERROR, "dataset does not match the gen-v1 format", mapOf("problems" to listOf(it.message?.take(300))))
        }
        val problems = parsed.problems()
        if (problems.isNotEmpty()) throw StayOpsException(ErrorCode.VALIDATION_ERROR, "dataset is not acceptable", mapOf("problems" to problems))

        val json = jsonMapper.writeValueAsString(payload)
        val contentHash = sha256(json)
        datasets.findByPropertyIdAndContentHash(propertyId, contentHash)?.let { return view(it, created = false) }

        val summary = DatasetSummary(
            name = parsed.property.name, seed = parsed.metadata.seed, generatorVersion = parsed.metadata.generatorVersion,
            configHash = parsed.metadata.configHash, startDate = parsed.metadata.horizon.startDate, days = parsed.metadata.horizon.days,
            timezone = parsed.property.timezone, rooms = parsed.rooms.size, staff = parsed.staff.size, reservations = parsed.reservations.size,
        )
        val entity = datasets.save(
            SimulationDatasetEntity(
                propertyId = propertyId, seed = parsed.metadata.seed, generatorVersion = parsed.metadata.generatorVersion,
                configHash = parsed.metadata.configHash, contentHash = contentHash, summaryJson = jsonMapper.writeValueAsString(summary),
                payloadJson = json, createdBy = ctx.actor.staffId, createdAt = ctx.now,
            ),
        )
        return view(entity, created = true)
    }

    @Transactional(readOnly = true)
    fun listDatasets(actor: Actor, propertyId: UUID): List<DatasetView> {
        requireReader(actor, propertyId)
        return datasets.findTop50ByPropertyIdOrderByCreatedAtDesc(propertyId).map { view(it, created = false) }
    }

    // ----------------------------------------------------------------- run

    @Transactional(propagation = Propagation.MANDATORY)
    fun run(ctx: CommandContext, propertyId: UUID, datasetId: UUID, requested: List<SimPolicy>?): SimulationRunView {
        ctx.actor.requireProperty(propertyId)
        ctx.actor.requireRole(StaffRole.MANAGER)
        val policies = (requested?.takeIf { it.isNotEmpty() } ?: SimPolicy.entries).distinct()
        if (policies.first() != SimPolicy.BASELINE_FIFO) {
            throw StayOpsException(ErrorCode.VALIDATION_ERROR, "the first policy must be the baseline ${SimPolicy.BASELINE_FIFO}")
        }
        val dataset = datasets.findById(datasetId).orElse(null)?.takeIf { it.propertyId == propertyId } ?: throw NotFoundException("dataset", datasetId)
        val parsed = jsonMapper.readValue(dataset.payloadJson, SimDataset::class.java)
        val parameters = SimParameters()

        val startedAt = ctx.now
        val metrics = policies.map { it to SimulationEngine.run(parsed, it, parameters) }
        val baseline = metrics.first()
        val scenarios = metrics.map { (policy, m) ->
            ScenarioResult(
                policy = policy, label = policy.label, dispatchRule = policy.dispatchRule,
                sameHousekeeperShiftStart = policy.sameHousekeeperShiftStart,
                parameters = parameterMap(policy, parameters), metrics = m,
                diffFromBaseline = if (policy == baseline.first) null else diff(baseline, policy to m, parameters),
            )
        }
        val entity = runs.save(
            SimulationRunEntity(
                propertyId = propertyId, datasetId = datasetId, seed = dataset.seed,
                engineVersion = SimulationEngine.ENGINE_VERSION, policyVersion = SimulationEngine.POLICY_VERSION,
                scenariosJson = jsonMapper.writeValueAsString(scenarios.map { mapOf("policy" to it.policy, "parameters" to it.parameters) }),
                resultsJson = jsonMapper.writeValueAsString(scenarios), status = "COMPLETED", createdBy = ctx.actor.staffId,
                startedAt = startedAt, completedAt = clock.instant().coerceAtLeast(startedAt), createdAt = startedAt,
            ),
        )
        return runView(entity, dataset)
    }

    @Transactional(readOnly = true)
    fun get(actor: Actor, propertyId: UUID, runId: UUID): SimulationRunView {
        requireReader(actor, propertyId)
        val run = runs.findById(runId).orElse(null)?.takeIf { it.propertyId == propertyId } ?: throw NotFoundException("simulation", runId)
        return runView(run, datasets.findById(run.datasetId).orElseThrow())
    }

    @Transactional(readOnly = true)
    fun list(actor: Actor, propertyId: UUID): List<SimulationRunSummary> {
        requireReader(actor, propertyId)
        return runs.findTop50ByPropertyIdOrderByCreatedAtDesc(propertyId).map { run ->
            val scenarios = readScenarios(run.resultsJson)
            SimulationRunSummary(
                runId = run.getId(), datasetId = run.datasetId, seed = run.seed, status = run.status, createdAt = run.createdAt,
                policies = scenarios.map { it.policy },
                onTimeReadinessRate = scenarios.associate { it.policy.name to it.metrics.onTimeReadinessRate },
            )
        }
    }

    // ------------------------------------------------------------- helpers

    /** 04 §5: simulation은 MANAGER 실행, FRONT_DESK 읽기. */
    private fun requireReader(actor: Actor, propertyId: UUID) {
        actor.requireProperty(propertyId)
        actor.requireRole(StaffRole.MANAGER, StaffRole.FRONT_DESK)
    }

    private fun parameterMap(policy: SimPolicy, p: SimParameters): Map<String, String> = linkedMapOf(
        "dispatchRule" to policy.dispatchRule.name,
        "housekeeperShiftStart" to if (policy.sameHousekeeperShiftStart) "ALL_${p.sameShiftStartTime}" else "DATASET",
        "stayoverRequestTime" to p.stayoverRequestTime.toString(),
    )

    private fun diff(baseline: Pair<SimPolicy, SimMetrics>, scenario: Pair<SimPolicy, SimMetrics>, p: SimParameters): ScenarioDiff {
        val b = headline(baseline.second)
        val s = headline(scenario.second)
        val bp = parameterMap(baseline.first, p)
        val sp = parameterMap(scenario.first, p)
        return ScenarioDiff(
            metrics = s.mapValues { (k, v) -> if (v == null || b[k] == null) null else round4(v - b[k]!!) },
            parameters = sp.filter { (k, v) -> bp[k] != v }.mapValues { (k, v) -> mapOf("baseline" to bp[k], "scenario" to v) },
        )
    }

    /** 비교 표에 쓰는 핵심 지표 (01 §6). */
    private fun headline(m: SimMetrics): Map<String, Double?> = linkedMapOf(
        "onTimeReadinessRate" to m.onTimeReadinessRate,
        "meanLatenessMinutes" to m.meanLatenessMinutes,
        "p90LatenessMinutes" to m.p90LatenessMinutes?.toDouble(),
        "late" to m.late.toDouble(),
        "notReadyByHorizonEnd" to m.notReadyByHorizonEnd.toDouble(),
        "taskCompletionRate" to m.taskCompletionRate,
        "housekeeperUtilization" to m.workload["HOUSEKEEPER"]?.utilization,
        "inspectorUtilization" to m.workload["INSPECTOR"]?.utilization,
        "overtimeMinutes" to m.workload.values.sumOf { it.overtimeMinutes }.toDouble(),
        "stayoverMeanWaitMinutes" to m.stayover.meanWaitMinutes,
    )

    private fun readScenarios(json: String): List<ScenarioResult> =
        jsonMapper.readValue(json, object : TypeReference<List<ScenarioResult>>() {})

    private fun runView(run: SimulationRunEntity, dataset: SimulationDatasetEntity) = SimulationRunView(
        runId = run.getId(), status = run.status, dataset = view(dataset, created = false), seed = run.seed,
        engineVersion = run.engineVersion, policyVersion = run.policyVersion, startedAt = run.startedAt, completedAt = run.completedAt,
        scenarios = readScenarios(run.resultsJson), definitions = SimulationDefinitions.kpi, assumptions = SimulationDefinitions.assumptions,
    )

    private fun view(entity: SimulationDatasetEntity, created: Boolean) = DatasetView(
        datasetId = entity.getId(), contentHash = entity.contentHash, createdAt = entity.createdAt, created = created,
        summary = jsonMapper.readValue(entity.summaryJson, DatasetSummary::class.java),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun round4(value: Double): Double = Math.round(value * 10_000.0) / 10_000.0
}

data class DatasetSummary(
    val name: String,
    val seed: Long,
    val generatorVersion: String,
    val configHash: String,
    val startDate: LocalDate,
    val days: Int,
    val timezone: String,
    val rooms: Int,
    val staff: Int,
    val reservations: Int,
)

data class DatasetView(
    val datasetId: UUID,
    val contentHash: String,
    val createdAt: Instant,
    /** 이번 요청으로 새로 저장됐는지(같은 내용이면 기존 데이터셋을 돌려준다). */
    val created: Boolean,
    val summary: DatasetSummary,
    val synthetic: Boolean = true,
)

data class ScenarioDiff(val metrics: Map<String, Double?>, val parameters: Map<String, Map<String, String?>>)

data class ScenarioResult(
    val policy: SimPolicy,
    val label: String,
    val dispatchRule: DispatchRule,
    val sameHousekeeperShiftStart: Boolean,
    val parameters: Map<String, String>,
    val metrics: SimMetrics,
    /** 기준 정책 대비 차이(시나리오 − 기준). 기준 자신은 null. */
    val diffFromBaseline: ScenarioDiff?,
)

data class SimulationRunView(
    val runId: UUID,
    val status: String,
    val dataset: DatasetView,
    val seed: Long,
    val engineVersion: String,
    val policyVersion: String,
    val startedAt: Instant,
    val completedAt: Instant?,
    val scenarios: List<ScenarioResult>,
    val definitions: Map<String, String>,
    val assumptions: List<String>,
    val synthetic: Boolean = true,
    val note: String = "합성 데이터에 대한 정책 간 상대 비교입니다. 실제 운영 성과나 최적화 보장이 아닙니다.",
)

data class SimulationRunSummary(
    val runId: UUID,
    val datasetId: UUID,
    val seed: Long,
    val status: String,
    val createdAt: Instant,
    val policies: List<SimPolicy>,
    val onTimeReadinessRate: Map<String, Double?>,
)
