package com.stayops.room.application

import com.stayops.operations.application.EventRecorder
import com.stayops.room.domain.Room
import com.stayops.room.domain.RoomAvailabilityStatus
import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.shared.command.CommandContext
import com.stayops.staff.domain.StaffRole
import com.stayops.workflow.lock.RoomWorkflowLocks
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 객실 판매 중단(OOS)·해제 (docs/v4.1/02 §5).
 * - operationalStatus를 바꾸지 않는다. 해제만으로 READY가 되지 않는다.
 * - 투숙 중 예약을 자동 퇴실·취소하지 않고, 진행 중 작업도 자동 완료·취소하지 않는다.
 * - 같은 상태로의 요청은 변경·이벤트 없이 성공한다.
 */
@Service
class RoomAvailabilityService(
    private val locks: RoomWorkflowLocks,
    private val events: EventRecorder,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun change(ctx: CommandContext, roomId: UUID, to: RoomAvailabilityStatus, reason: String): RoomCommandResponse {
        ctx.actor.requireRole(StaffRole.MANAGER)
        val room = locks.lockRoom(ctx.actor.propertyId, roomId).room
        val from = room.availabilityStatus
        val changed = room.changeAvailability(to, reason.trim(), ctx.now)
        if (changed) {
            events.record(
                ctx, room.propertyId, "Room", room, "ROOM_AVAILABILITY_CHANGED",
                mapOf("from" to from.name, "to" to to.name, "reason" to reason.trim(), "operationalStatus" to room.operationalStatus.name),
            )
        }
        return RoomCommandResponse.of(ctx, room, changed)
    }
}

data class RoomCommandResponse(
    val roomId: UUID,
    val operationalStatus: RoomOperationalStatus,
    val availabilityStatus: RoomAvailabilityStatus,
    val availabilityReason: String?,
    val roomVersion: Long,
    val changed: Boolean,
    val occurredAt: Instant,
    val correlationId: UUID,
) {
    companion object {
        fun of(ctx: CommandContext, room: Room, changed: Boolean) = RoomCommandResponse(
            roomId = room.getId(), operationalStatus = room.operationalStatus, availabilityStatus = room.availabilityStatus,
            availabilityReason = room.availabilityReason, roomVersion = room.version, changed = changed,
            occurredAt = ctx.now, correlationId = ctx.correlationId,
        )
    }
}
