package com.stayops.housekeeping.api

import com.stayops.housekeeping.application.TaskCommandResponse
import com.stayops.housekeeping.application.TaskCommandService
import com.stayops.housekeeping.domain.BlockerType
import com.stayops.housekeeping.domain.InspectionResult
import com.stayops.identity.Actor
import com.stayops.shared.command.CommandRequest
import com.stayops.shared.command.CommandResult
import com.stayops.shared.command.IdempotentCommandExecutor
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** 작업 lifecycle command (docs/v4.1/02 §6). */
@RestController
@RequestMapping("/api/v1/tasks")
class TaskCommandController(
    private val executor: IdempotentCommandExecutor,
    private val service: TaskCommandService,
) {
    data class AssignRequest(@field:NotNull val staffId: UUID?)

    data class BlockerRequest(
        @field:NotNull val type: BlockerType?,
        @field:Size(max = 500) val description: String? = null,
        val estimatedResolutionAt: Instant? = null,
    )

    data class ResolveBlockerRequest(@field:Size(max = 500) val note: String? = null)

    data class InspectionResultRequest(
        @field:NotNull val result: InspectionResult?,
        @field:Size(max = 500) val failureReason: String? = null,
        @field:Size(max = 50) val checklist: Map<String, Boolean>? = null,
    )

    data class CancelRequest(@field:NotBlank @field:Size(max = 500) val reason: String?)

    @PostMapping("/{taskId}/assignments")
    fun assign(@PathVariable taskId: UUID, @Valid @RequestBody body: AssignRequest, actor: Actor, command: CommandRequest) =
        run(command, actor, mapOf("taskId" to taskId, "body" to body), HttpStatus.OK) { service.assign(it, taskId, body.staffId!!) }

    @DeleteMapping("/{taskId}/assignments/{assignmentId}")
    fun unassign(@PathVariable taskId: UUID, @PathVariable assignmentId: UUID, actor: Actor, command: CommandRequest) =
        run(command, actor, mapOf("taskId" to taskId, "assignmentId" to assignmentId)) { service.unassign(it, taskId, assignmentId) }

    @PostMapping("/{taskId}/start")
    fun start(@PathVariable taskId: UUID, actor: Actor, command: CommandRequest) =
        run(command, actor, mapOf("taskId" to taskId)) { service.start(it, taskId) }

    @PostMapping("/{taskId}/blockers")
    fun addBlocker(@PathVariable taskId: UUID, @Valid @RequestBody body: BlockerRequest, actor: Actor, command: CommandRequest) =
        run(command, actor, mapOf("taskId" to taskId, "body" to body), HttpStatus.CREATED) {
            service.addBlocker(it, taskId, body.type!!, body.description, body.estimatedResolutionAt)
        }

    @PostMapping("/{taskId}/blockers/{blockerId}/resolve")
    fun resolveBlocker(
        @PathVariable taskId: UUID,
        @PathVariable blockerId: UUID,
        @Valid @RequestBody(required = false) body: ResolveBlockerRequest?,
        actor: Actor,
        command: CommandRequest,
    ) = run(command, actor, mapOf("taskId" to taskId, "blockerId" to blockerId, "body" to body)) {
        service.resolveBlocker(it, taskId, blockerId, body?.note)
    }

    @PostMapping("/{taskId}/complete")
    fun complete(@PathVariable taskId: UUID, actor: Actor, command: CommandRequest) =
        run(command, actor, mapOf("taskId" to taskId)) { service.complete(it, taskId) }

    @PostMapping("/{taskId}/inspection-result")
    fun inspectionResult(@PathVariable taskId: UUID, @Valid @RequestBody body: InspectionResultRequest, actor: Actor, command: CommandRequest) =
        run(command, actor, mapOf("taskId" to taskId, "body" to body)) {
            service.submitInspectionResult(it, taskId, body.result!!, body.failureReason, body.checklist ?: emptyMap())
        }

    @PostMapping("/{taskId}/cancel")
    fun cancel(@PathVariable taskId: UUID, @Valid @RequestBody body: CancelRequest, actor: Actor, command: CommandRequest) =
        run(command, actor, mapOf("taskId" to taskId, "body" to body)) { service.cancel(it, taskId, body.reason!!) }

    private fun run(
        command: CommandRequest,
        actor: Actor,
        payload: Any,
        status: HttpStatus = HttpStatus.OK,
        action: (com.stayops.shared.command.CommandContext) -> TaskCommandResponse,
    ): ResponseEntity<TaskCommandResponse> =
        executor.execute(command, actor, payload, TaskCommandResponse::class.java) { ctx -> CommandResult(action(ctx), status) }
            .toResponseEntity()
}
