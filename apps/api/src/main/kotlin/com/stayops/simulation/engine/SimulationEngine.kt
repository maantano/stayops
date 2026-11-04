package com.stayops.simulation.engine

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.PriorityQueue

/** 작업 배정 규칙. */
enum class DispatchRule {
    /** 먼저 대기열에 들어온 작업부터. */
    FIFO,

    /** 다음 도착의 준비 마감이 이른 객실부터(readiness 기반). 마감이 없는 작업(투숙 중 청소 등)은 뒤로. */
    DEADLINE_FIRST,
}

/** 비교할 운영 정책 (P11에서 사용자 승인). 첫 번째(BASELINE_FIFO)가 기준이다. */
enum class SimPolicy(val dispatchRule: DispatchRule, val sameHousekeeperShiftStart: Boolean, val label: String) {
    BASELINE_FIFO(DispatchRule.FIFO, false, "기준: 먼저 생긴 작업부터 · 교대 시작 분산"),
    DEADLINE_FIRST(DispatchRule.DEADLINE_FIRST, false, "대안 A: 준비 마감이 급한 객실부터"),
    SAME_SHIFT_START(DispatchRule.FIFO, true, "대안 B: 하우스키핑 교대 시작을 모두 같은 시각으로"),
}

/** 정책 밖의 시뮬레이션 가정 (모두 ASSUMPTION). */
data class SimParameters(
    /** SAME_SHIFT_START에서 하우스키핑 근무 시작 현지 시각. */
    val sameShiftStartTime: LocalTime = LocalTime.of(9, 0),
    /** 투숙 중 청소 요청이 대기열에 들어오는 현지 시각. */
    val stayoverRequestTime: LocalTime = LocalTime.of(10, 0),
)

/**
 * 결정적 하우스키핑 시뮬레이터 (docs/v4.1/02 §9, 01 §5 H3).
 *
 * 모델 (모두 ASSUMPTION, 결과에 함께 기록):
 * - 시뮬레이션 기간 [시작일 00:00, 시작일+days 00:00) (property 현지). 기간 시작 시 투숙 중이 아닌 객실은 준비 완료로 본다.
 * - 투숙객은 예정 체크아웃 시각에 퇴실한다. 기간 안의 체크아웃마다 turnover가 생긴다.
 * - 린넨 대기·시설 차단은 체크아웃 후 청소를 시작할 수 있을 때까지의 지연으로 둔다(작업자는 그동안 다른 일을 한다).
 * - 청소 → 검사 → (실패) 재청소 → 재검사(통과) → 준비 완료. 시간은 데이터셋 realization 값을 그대로 쓴다.
 * - 투숙 중 청소 요청은 요청일 현지 [SimParameters.stayoverRequestTime]에 대기열에 들어간다(마감 없음).
 * - 작업자는 근무 시간 안, 휴게(근무 중간) 밖에서만 새 작업을 시작한다. 시작한 작업은 끝까지 하며 근무 종료 이후분은 초과근무.
 * - 이동·준비 시간, 동시 작업, 결근은 모델링하지 않는다.
 * 난수를 쓰지 않는다. 같은 데이터셋·정책·파라미터 → 같은 결과. 동시 결정은 작업자 이름, 작업 생성 순서로 정한다.
 */
object SimulationEngine {
    const val ENGINE_VERSION = "sim-engine-v1"
    const val POLICY_VERSION = "sim-policy-v1"

    private const val HOUSEKEEPER = "HOUSEKEEPER"
    private const val INSPECTOR = "INSPECTOR"

    enum class JobKind(val role: String) {
        CLEANING(HOUSEKEEPER),
        INSPECTION(INSPECTOR),
        RE_CLEANING(HOUSEKEEPER),
        RE_INSPECTION(INSPECTOR),
        STAYOVER(HOUSEKEEPER),
    }

    private class Turnover(val departing: SimDataset.SimReservation, val checkoutAt: Long, val deadline: Long?) {
        var readyAt: Long? = null
    }

    private class Job(
        val seq: Int,
        val kind: JobKind,
        val releaseAt: Long,
        val duration: Int,
        val deadline: Long?,
        val turnover: Turnover?,
    ) {
        var startAt: Long? = null
        var endAt: Long? = null
    }

    private class Window(val start: Long, val end: Long, val breakStart: Long, val breakEnd: Long)

    private class Worker(val name: String, val role: String, val windows: List<Window>) {
        var busyUntil: Long = Long.MIN_VALUE
        var busyInShift: Long = 0
        var overtime: Long = 0

        fun windowAt(t: Long): Window? = windows.firstOrNull { t >= it.start && t < it.end && !(t >= it.breakStart && t < it.breakEnd) }
    }

    fun run(dataset: SimDataset, policy: SimPolicy, parameters: SimParameters = SimParameters()): SimMetrics {
        val zone = ZoneId.of(dataset.property.timezone)
        val horizon = dataset.metadata.horizon
        val horizonStart = minutes(horizon.startDate.atStartOfDay(zone).toInstant())
        val horizonEnd = minutes(horizon.startDate.plusDays(horizon.days.toLong()).atStartOfDay(zone).toInstant())
        val buffer = dataset.property.operationalBufferMinutes.toLong()

        // ---------------------------------------------------------- 작업 생성
        var seq = 0
        val pending = PriorityQueue(compareBy<Job>({ it.releaseAt }, { it.seq }))
        val allJobs = mutableListOf<Job>()
        fun enqueue(kind: JobKind, releaseAt: Long, duration: Int, deadline: Long?, turnover: Turnover?): Job =
            Job(seq++, kind, releaseAt, duration, deadline, turnover).also { pending += it; allJobs += it }

        val turnovers = mutableListOf<Turnover>()
        dataset.reservations.groupBy { it.roomId }.toSortedMap().forEach { (_, stays) ->
            val ordered = stays.sortedBy { it.scheduledCheckInAt }
            ordered.forEachIndexed { i, stay ->
                val checkoutAt = minutes(stay.scheduledCheckOutAt)
                if (checkoutAt < horizonStart || checkoutAt >= horizonEnd) return@forEachIndexed
                val next = ordered.getOrNull(i + 1)?.takeIf { !it.scheduledCheckInAt.isBefore(stay.scheduledCheckOutAt) }
                val turnover = Turnover(stay, checkoutAt, next?.let { minutes(it.scheduledCheckInAt) - buffer })
                turnovers += turnover
                val blockedUntil = checkoutAt + stay.realization.linenWaitMinutes + stay.realization.maintenanceBlockMinutes
                enqueue(JobKind.CLEANING, blockedUntil, stay.realization.checkoutCleaningMinutes, turnover.deadline, turnover)
            }
        }
        dataset.reservations.sortedBy { it.reservationId }.forEach { stay ->
            stay.realization.stayoverRequests.forEach { req ->
                val releaseAt = minutes(req.date.atTime(parameters.stayoverRequestTime).atZone(zone).toInstant())
                if (releaseAt in horizonStart until horizonEnd) enqueue(JobKind.STAYOVER, releaseAt, req.minutes, null, null)
            }
        }

        // ------------------------------------------------------- 작업자·근무
        val staffById = dataset.staff.associateBy { it.staffId }
        val workers = dataset.shifts.groupBy { it.staffId }.mapNotNull { (staffId, shifts) ->
            val member = staffById[staffId] ?: return@mapNotNull null
            val windows = shifts.map { shift ->
                var start = shift.startsAt
                var end = shift.endsAt
                if (policy.sameHousekeeperShiftStart && member.role == HOUSEKEEPER) {
                    val localDate = start.atZone(zone).toLocalDate()
                    val length = Duration.between(start, end)
                    start = localDate.atTime(parameters.sameShiftStartTime).atZone(zone).toInstant()
                    end = start.plus(length)
                }
                val s = minutes(start)
                val e = minutes(end)
                val breakStart = s + (e - s - shift.breakMinutes) / 2
                Window(s, e, breakStart, breakStart + shift.breakMinutes)
            }.sortedBy { it.start }
            Worker(member.displayName, member.role, windows)
        }.sortedBy { it.name }

        // ---------------------------------------------------------- 시뮬레이션
        val available = mutableMapOf(HOUSEKEEPER to mutableListOf<Job>(), INSPECTOR to mutableListOf())
        val running = PriorityQueue(compareBy<Job>({ it.endAt }, { it.seq }))
        val order: Comparator<Job> = when (policy.dispatchRule) {
            DispatchRule.FIFO -> compareBy({ it.releaseAt }, { it.seq })
            DispatchRule.DEADLINE_FIRST -> compareBy<Job>({ it.deadline ?: Long.MAX_VALUE }, { it.releaseAt }, { it.seq })
        }

        fun complete(job: Job) {
            val end = job.endAt!!
            val turnover = job.turnover ?: return
            val r = turnover.departing.realization
            when (job.kind) {
                JobKind.CLEANING -> enqueue(JobKind.INSPECTION, end, r.inspectionMinutes, turnover.deadline, turnover)
                JobKind.INSPECTION ->
                    if (r.inspectionFails) enqueue(JobKind.RE_CLEANING, end, r.reCleaningMinutes!!, turnover.deadline, turnover) else turnover.readyAt = end
                JobKind.RE_CLEANING -> enqueue(JobKind.RE_INSPECTION, end, r.reInspectionMinutes!!, turnover.deadline, turnover)
                JobKind.RE_INSPECTION -> turnover.readyAt = end
                JobKind.STAYOVER -> Unit
            }
        }

        for (t in horizonStart until horizonEnd) {
            while (running.isNotEmpty() && running.peek().endAt!! <= t) complete(running.poll())
            while (pending.isNotEmpty() && pending.peek().releaseAt <= t) pending.poll().let { available.getValue(it.kind.role) += it }
            for (worker in workers) {
                if (worker.busyUntil > t) continue
                val queue = available.getValue(worker.role)
                if (queue.isEmpty()) continue
                val window = worker.windowAt(t) ?: continue
                val job = queue.minWith(order)
                queue.remove(job)
                job.startAt = t
                job.endAt = t + job.duration
                worker.busyUntil = job.endAt!!
                val overtime = maxOf(0L, job.endAt!! - window.end)
                worker.overtime += overtime
                worker.busyInShift += job.duration - overtime
                running += job
            }
        }
        while (running.isNotEmpty() && running.peek().endAt!! <= horizonEnd) complete(running.poll())

        return metrics(turnovers, allJobs, workers, horizonStart, horizonEnd, horizonEnd)
    }

    private fun metrics(
        turnovers: List<Turnover>,
        jobs: List<Job>,
        workers: List<Worker>,
        horizonStart: Long,
        horizonEnd: Long,
        cutoff: Long,
    ): SimMetrics {
        val evaluated = turnovers.filter { it.deadline != null && it.deadline < horizonEnd }
        val onTime = evaluated.count { it.readyAt != null && it.readyAt!! <= it.deadline!! }
        val lateReady = evaluated.filter { it.readyAt != null && it.readyAt!! > it.deadline!! }
        val notReady = evaluated.count { it.readyAt == null }
        val lateness = lateReady.map { it.readyAt!! - it.deadline!! }.sorted()

        fun completed(job: Job) = job.endAt != null && job.endAt!! <= cutoff
        val jobStats = JobKind.entries.associate { kind ->
            val ofKind = jobs.filter { it.kind == kind }
            kind.name to SimMetrics.JobStats(created = ofKind.size, completed = ofKind.count(::completed), incomplete = ofKind.count { !completed(it) })
        }
        val totalJobs = jobs.size
        val totalCompleted = jobs.count(::completed)

        val workload = listOf(HOUSEKEEPER, INSPECTOR).associateWith { role ->
            val ws = workers.filter { it.role == role }
            val availableMinutes = ws.sumOf { w ->
                w.windows.sumOf { win ->
                    val from = maxOf(win.start, horizonStart)
                    val to = minOf(win.end, horizonEnd)
                    maxOf(0L, to - from - (win.breakEnd - win.breakStart))
                }
            }
            val busy = ws.sumOf { it.busyInShift }
            SimMetrics.RoleLoad(
                staff = ws.size,
                availableMinutes = availableMinutes,
                busyMinutes = busy,
                utilization = if (availableMinutes > 0) round4(busy.toDouble() / availableMinutes) else null,
                overtimeMinutes = ws.sumOf { it.overtime },
            )
        }

        val stayovers = jobs.filter { it.kind == JobKind.STAYOVER }
        val started = stayovers.filter { it.startAt != null }
        return SimMetrics(
            turnovers = turnovers.size,
            evaluatedArrivals = evaluated.size,
            onTime = onTime,
            late = lateReady.size,
            notReadyByHorizonEnd = notReady,
            onTimeReadinessRate = if (evaluated.isEmpty()) null else round4(onTime.toDouble() / evaluated.size),
            meanLatenessMinutes = if (lateness.isEmpty()) null else round4(lateness.average()),
            p90LatenessMinutes = if (lateness.isEmpty()) null else lateness[maxOf(0, Math.ceil(0.9 * lateness.size).toInt() - 1)],
            maxLatenessMinutes = lateness.maxOrNull(),
            inspectionFailures = turnovers.count { it.departing.realization.inspectionFails },
            jobs = jobStats,
            taskCompletionRate = if (totalJobs == 0) null else round4(totalCompleted.toDouble() / totalJobs),
            workload = workload,
            stayover = SimMetrics.StayoverStats(
                requested = stayovers.size,
                started = started.size,
                completed = stayovers.count(::completed),
                meanWaitMinutes = if (started.isEmpty()) null else round4(started.map { it.startAt!! - it.releaseAt }.average()),
            ),
        )
    }

    private fun minutes(instant: Instant): Long = Math.floorDiv(instant.epochSecond, 60L)

    private fun round4(value: Double): Double = Math.round(value * 10_000.0) / 10_000.0
}

/**
 * 시뮬레이션 KPI (docs/v4.1/01 §6). 합성 데이터에 대한 정책 간 **상대 비교**용이며 운영 성과가 아니다.
 * 분모·기준은 [SimulationDefinitions]에 함께 기록한다.
 */
data class SimMetrics(
    val turnovers: Int,
    val evaluatedArrivals: Int,
    val onTime: Int,
    val late: Int,
    val notReadyByHorizonEnd: Int,
    val onTimeReadinessRate: Double?,
    val meanLatenessMinutes: Double?,
    val p90LatenessMinutes: Long?,
    val maxLatenessMinutes: Long?,
    val inspectionFailures: Int,
    val jobs: Map<String, JobStats>,
    val taskCompletionRate: Double?,
    val workload: Map<String, RoleLoad>,
    val stayover: StayoverStats,
) {
    data class JobStats(val created: Int, val completed: Int, val incomplete: Int)

    data class RoleLoad(val staff: Int, val availableMinutes: Long, val busyMinutes: Long, val utilization: Double?, val overtimeMinutes: Long)

    data class StayoverStats(val requested: Int, val started: Int, val completed: Int, val meanWaitMinutes: Double?)
}

/** KPI 정의 (01 §6: 분모·기간·제외 기준을 결과와 함께 표시). */
object SimulationDefinitions {
    val kpi: Map<String, String> = linkedMapOf(
        "onTimeReadinessRate" to "기간 안에 준비 마감이 있는 turnover(다음 도착이 있는 체크아웃) 중 마감 이전 준비 완료 비율. 분모 = evaluatedArrivals",
        "meanLatenessMinutes" to "마감 이후 준비된 turnover의 (준비 시각 − 마감) 평균. 기간 끝까지 준비되지 않은 turnover는 제외하고 notReadyByHorizonEnd로 따로 센다",
        "notReadyByHorizonEnd" to "준비 마감이 기간 안인데 기간 종료까지 준비되지 않은 turnover 수",
        "taskCompletionRate" to "기간 안에 생성된 작업 중 기간 종료 전 완료된 비율",
        "utilization" to "역할별 근무 중 작업 시간 / 근무 가능 시간(근무 − 휴게). 초과근무 시간은 분자에서 제외하고 overtimeMinutes로 따로 표시",
        "stayover.meanWaitMinutes" to "투숙 중 청소 요청이 대기열에 들어온 뒤 시작까지 걸린 평균 분",
    )

    val assumptions: List<String> = listOf(
        "기간 시작 시 투숙 중이 아닌 객실은 준비 완료로 본다",
        "투숙객은 예정 체크아웃 시각에 퇴실한다",
        "린넨 대기·시설 차단은 청소 시작 전 지연으로 모델링한다(작업자는 그동안 다른 작업 가능)",
        "검사 실패 시 재청소 후 재검사는 통과한다",
        "작업자는 근무 시간 안·휴게 밖에서만 새 작업을 시작하고, 시작한 작업은 끝까지 한다",
        "이동·준비 시간, 동시 작업, 결근은 반영하지 않는다",
        "투숙 중 청소 요청은 요청일 현지 10:00에 대기열에 들어간다",
    )
}
