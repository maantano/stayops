package com.stayops.simulation.api

import com.stayops.identity.Actor
import com.stayops.shared.command.CommandRequest
import com.stayops.shared.command.CommandResult
import com.stayops.shared.command.IdempotentCommandExecutor
import com.stayops.simulation.application.DatasetView
import com.stayops.simulation.application.SimulationRunSummary
import com.stayops.simulation.application.SimulationRunView
import com.stayops.simulation.application.SimulationService
import com.stayops.simulation.engine.SimPolicy
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 시뮬레이션 (docs/v4.1/02 §6·§9). 데이터셋 import·실행은 MANAGER, 조회는 MANAGER·FRONT_DESK. */
@RestController
@RequestMapping("/api/v1/properties/{propertyId}")
class SimulationController(
    private val executor: IdempotentCommandExecutor,
    private val service: SimulationService,
) {
    data class RunRequest(
        @field:NotNull val datasetId: UUID?,
        /** 생략하면 세 정책 모두. 첫 번째는 기준(BASELINE_FIFO)이어야 한다. */
        val policies: List<SimPolicy>? = null,
    )

    /** P10 생성기 출력(gen-v1) JSON을 그대로 올린다. 같은 내용이면 기존 데이터셋을 돌려준다(created=false). */
    @PostMapping("/simulation-datasets")
    fun importSimulationDataset(
        @PathVariable propertyId: UUID,
        @RequestBody dataset: Map<String, Any?>,
        actor: Actor,
        command: CommandRequest,
    ): ResponseEntity<DatasetView> =
        executor.execute(command, actor, mapOf("propertyId" to propertyId, "dataset" to dataset), DatasetView::class.java) { ctx ->
            val view = service.importDataset(ctx, propertyId, dataset)
            CommandResult(view, if (view.created) HttpStatus.CREATED else HttpStatus.OK)
        }.toResponseEntity()

    @GetMapping("/simulation-datasets")
    fun listSimulationDatasets(@PathVariable propertyId: UUID, actor: Actor): List<DatasetView> = service.listDatasets(actor, propertyId)

    /** 기준과 대안 정책을 같은 데이터셋으로 실행해 비교한다 (요청 안에서 동기 실행). */
    @PostMapping("/simulations")
    fun runSimulation(@PathVariable propertyId: UUID, @Valid @RequestBody body: RunRequest, actor: Actor, command: CommandRequest): ResponseEntity<SimulationRunView> =
        executor.execute(command, actor, mapOf("propertyId" to propertyId, "body" to body), SimulationRunView::class.java) { ctx ->
            CommandResult(service.run(ctx, propertyId, body.datasetId!!, body.policies), HttpStatus.CREATED)
        }.toResponseEntity()

    @GetMapping("/simulations")
    fun listSimulations(@PathVariable propertyId: UUID, actor: Actor): List<SimulationRunSummary> = service.list(actor, propertyId)

    @GetMapping("/simulations/{simulationId}")
    fun getSimulation(@PathVariable propertyId: UUID, @PathVariable simulationId: UUID, actor: Actor): SimulationRunView =
        service.get(actor, propertyId, simulationId)
}
