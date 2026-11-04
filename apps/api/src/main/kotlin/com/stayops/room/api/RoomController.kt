package com.stayops.room.api

import com.stayops.housekeeping.application.RoomTaskCommandService
import com.stayops.housekeeping.application.TaskCommandResponse
import com.stayops.identity.Actor
import com.stayops.room.application.RoomAvailabilityService
import com.stayops.room.application.RoomCommandResponse
import com.stayops.room.application.RoomQueryService
import com.stayops.room.application.RoomView
import com.stayops.room.application.TimelineEntry
import com.stayops.shared.web.PageResponse
import com.stayops.workflow.WorkflowStage
import org.springframework.web.bind.annotation.RequestParam
import com.stayops.room.domain.RoomAvailabilityStatus
import com.stayops.shared.command.CommandRequest
import com.stayops.shared.command.CommandResult
import com.stayops.shared.command.IdempotentCommandExecutor
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** 객실 기준 command·조회 (docs/v4.1/02 §6). */
@RestController
class RoomController(
    private val executor: IdempotentCommandExecutor,
    private val roomTasks: RoomTaskCommandService,
    private val availability: RoomAvailabilityService,
    private val queries: RoomQueryService,
) {
    data class StayoverRequest(
        val scheduledAt: Instant? = null,
        @field:Min(1) @field:Max(480) val estimatedDurationMinutes: Int? = null,
        @field:Size(max = 500) val notes: String? = null,
    )

    data class ReworkRequest(
        @field:NotNull val kind: RoomTaskCommandService.ReworkKind?,
        @field:NotBlank @field:Size(max = 500) val reason: String?,
        @field:Min(1) @field:Max(480) val estimatedDurationMinutes: Int? = null,
        val scheduledAt: Instant? = null,
        val assigneeStaffId: UUID? = null,
    )

    data class AvailabilityRequest(
        @field:NotNull val status: RoomAvailabilityStatus?,
        @field:NotBlank @field:Size(max = 500) val reason: String?,
    )

    @PostMapping("/api/v1/rooms/{roomId}/stayover-cleanings")
    fun createStayover(
        @PathVariable roomId: UUID,
        @Valid @RequestBody(required = false) body: StayoverRequest?,
        actor: Actor,
        command: CommandRequest,
    ): ResponseEntity<TaskCommandResponse> {
        val request = body ?: StayoverRequest()
        return executor.execute(command, actor, mapOf("roomId" to roomId, "body" to request), TaskCommandResponse::class.java) { ctx ->
            CommandResult(roomTasks.createStayover(ctx, roomId, request.scheduledAt, request.estimatedDurationMinutes, request.notes), HttpStatus.CREATED)
        }.toResponseEntity()
    }

    @PostMapping("/api/v1/rooms/{roomId}/rework-tasks")
    fun createRework(@PathVariable roomId: UUID, @Valid @RequestBody body: ReworkRequest, actor: Actor, command: CommandRequest): ResponseEntity<TaskCommandResponse> =
        executor.execute(command, actor, mapOf("roomId" to roomId, "body" to body), TaskCommandResponse::class.java) { ctx ->
            CommandResult(
                roomTasks.createRework(ctx, roomId, body.kind!!, body.reason!!, body.estimatedDurationMinutes, body.scheduledAt, body.assigneeStaffId),
                HttpStatus.CREATED,
            )
        }.toResponseEntity()

    @PostMapping("/api/v1/rooms/{roomId}/availability")
    fun changeAvailability(@PathVariable roomId: UUID, @Valid @RequestBody body: AvailabilityRequest, actor: Actor, command: CommandRequest): ResponseEntity<RoomCommandResponse> =
        executor.execute(command, actor, mapOf("roomId" to roomId, "body" to body), RoomCommandResponse::class.java) { ctx ->
            CommandResult(availability.change(ctx, roomId, body.status!!, body.reason!!))
        }.toResponseEntity()

    @GetMapping("/api/v1/properties/{propertyId}/rooms/{roomId}")
    fun room(@PathVariable propertyId: UUID, @PathVariable roomId: UUID, actor: Actor): RoomView = queries.room(actor, propertyId, roomId)

    @GetMapping("/api/v1/properties/{propertyId}/rooms")
    fun rooms(
        @PathVariable propertyId: UUID,
        @RequestParam(required = false) stage: WorkflowStage?,
        @RequestParam(required = false) floor: Int?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
        actor: Actor,
    ): PageResponse<RoomView> = queries.rooms(actor, propertyId, stage, floor, cursor, limit)

    @GetMapping("/api/v1/properties/{propertyId}/rooms/{roomId}/timeline")
    fun timeline(
        @PathVariable propertyId: UUID,
        @PathVariable roomId: UUID,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
        actor: Actor,
    ): PageResponse<TimelineEntry> = queries.timeline(actor, propertyId, roomId, cursor, limit)
}
