package com.stayops.command

import com.stayops.identity.Actor
import com.stayops.operations.application.EventRecorder
import com.stayops.shared.command.CommandRequest
import com.stayops.shared.command.CommandResult
import com.stayops.shared.command.IdempotentCommandExecutor
import com.stayops.shared.error.ErrorCode
import com.stayops.shared.error.StateInconsistencyException
import com.stayops.shared.error.StayOpsException
import com.stayops.workflow.lock.RoomWorkflowLocks
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 테스트가 "잠금을 실제로 잡았다"는 시점을 시간 추측 없이 기다리기 위한 신호. */
object ProbeSignals {
    @Volatile
    var lockAcquired = java.util.concurrent.CountDownLatch(1)
}

/**
 * P2.5 command 인프라 검증 전용 컨트롤러. `command-probe` profile에서만 등록된다.
 * 실제 도메인 command가 아니며 운영 코드에 포함되지 않는다 (src/test).
 */
@Profile("command-probe")
@RestController
class CommandProbeController(
    private val executor: IdempotentCommandExecutor,
    private val locks: RoomWorkflowLocks,
    private val events: EventRecorder,
) {
    data class ProbeRequest(
        @field:NotBlank @field:Size(max = 50) val note: String,
        val holdMillis: Long = 0,
        val fail: String? = null,
    )

    data class ProbeResponse(
        val roomId: UUID,
        val note: String,
        val eventId: UUID,
        val correlationId: UUID,
    )

    @PostMapping("/api/v1/test/rooms/{roomId}/probe")
    fun probe(
        @PathVariable roomId: UUID,
        @Valid @RequestBody body: ProbeRequest,
        actor: Actor,
        command: CommandRequest,
    ): ResponseEntity<ProbeResponse> =
        executor.execute(command, actor, mapOf("roomId" to roomId, "body" to body), ProbeResponse::class.java) { ctx ->
            val scope = locks.lockRoom(actor.propertyId, roomId)
            ProbeSignals.lockAcquired.countDown()
            if (body.holdMillis > 0) Thread.sleep(body.holdMillis)
            when (body.fail) {
                "DOMAIN" -> throw StayOpsException(
                    ErrorCode.INVALID_STATE, "probe rejected", mapOf("currentStatus" to scope.room.operationalStatus.name),
                )
                "INCONSISTENT" -> throw StateInconsistencyException("probe inconsistency", mapOf("secret" to "diagnostic-only"))
                "LOCK_ORDER" -> {
                    scope.activeTask()
                    scope.checkedInReservation()
                }
            }
            val event = events.record(ctx, actor.propertyId, "Room", scope.room, "PROBE_EXECUTED", mapOf("note" to body.note))
            CommandResult(ProbeResponse(roomId, body.note, event.getId(), ctx.correlationId))
        }.toResponseEntity()
}
