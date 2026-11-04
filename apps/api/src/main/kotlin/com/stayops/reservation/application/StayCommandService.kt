package com.stayops.reservation.application

import com.stayops.housekeeping.application.HousekeepingProperties
import com.stayops.housekeeping.domain.BlockerResolutionType
import com.stayops.housekeeping.domain.CancellationCode
import com.stayops.housekeeping.domain.DurationSource
import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.TaskType
import com.stayops.housekeeping.domain.UnassignedReason
import com.stayops.housekeeping.infrastructure.HousekeepingTaskRepository
import com.stayops.housekeeping.infrastructure.TaskAssignmentRepository
import com.stayops.housekeeping.infrastructure.TaskBlockerRepository
import com.stayops.identity.Actor
import com.stayops.operations.application.EventRecorder
import com.stayops.reservation.domain.Reservation
import com.stayops.reservation.domain.ReservationStatus
import com.stayops.room.domain.Room
import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.shared.command.CommandContext
import com.stayops.shared.error.ErrorCode
import com.stayops.shared.error.StateInconsistencyException
import com.stayops.shared.error.StayOpsException
import com.stayops.staff.domain.StaffRole
import com.stayops.staff.infrastructure.StaffRepository
import com.stayops.workflow.lock.RoomWorkflowLocks
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 예약 check-in / check-out command (v4.1 02 §4).
 * transaction·Idempotency-Key는 [com.stayops.shared.command.IdempotentCommandExecutor]가 연다 (MANDATORY).
 */
@Service
class StayCommandService(
    private val locks: RoomWorkflowLocks,
    private val events: EventRecorder,
    private val tasks: HousekeepingTaskRepository,
    private val assignments: TaskAssignmentRepository,
    private val blockers: TaskBlockerRepository,
    private val staff: StaffRepository,
    private val housekeeping: HousekeepingProperties,
) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun checkIn(ctx: CommandContext, reservationId: UUID): StayCommandResponse {
        ctx.actor.requireRole(StaffRole.FRONT_DESK, StaffRole.MANAGER)
        val (scope, reservation) = locks.lockRoomOfReservation(ctx.actor.propertyId, reservationId)
        val room = scope.room

        val occupant = scope.checkedInReservation()
        if (occupant != null && occupant.getId() != reservation.getId()) {
            throw StayOpsException(ErrorCode.INVALID_STATE, "room is occupied by another stay", mapOf("currentStatus" to reservation.status.name))
        }

        val roomBefore = room.operationalStatus
        reservation.checkIn(ctx.now)
        room.admitGuest()

        events.record(ctx, room.propertyId, "Reservation", reservation, "RESERVATION_CHECKED_IN", mapOf("roomId" to room.getId(), "actualCheckInAt" to ctx.now.toString()))
        recordRoomStatusChanged(ctx, room, roomBefore, "CHECK_IN", reservation.getId())
        return StayCommandResponse.of(ctx, reservation, room)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun checkOut(ctx: CommandContext, reservationId: UUID): StayCommandResponse {
        ctx.actor.requireRole(StaffRole.FRONT_DESK, StaffRole.MANAGER)
        val (scope, reservation) = locks.lockRoomOfReservation(ctx.actor.propertyId, reservationId)
        val room = scope.room

        if (reservation.status != ReservationStatus.CHECKED_IN) {
            throw StayOpsException(ErrorCode.INVALID_STATE, "reservation is ${reservation.status}", mapOf("currentStatus" to reservation.status.name))
        }

        // 시작 여부는 status가 아니라 actualStartedAt으로 판정한다 (v4.1 02 §2.8, N1).
        val active = scope.activeTask()
        if (active != null) {
            if (active.type != TaskType.STAYOVER_CLEANING) {
                throw StateInconsistencyException(
                    "checked-in room has an active turnover task",
                    mapOf("roomId" to room.getId(), "taskId" to active.getId(), "taskType" to active.type, "reservationId" to reservationId),
                )
            }
            if (active.isStarted) throw stayoverInProgress(active, ctx.actor)
        }
        if (room.operationalStatus != RoomOperationalStatus.OCCUPIED) {
            throw StateInconsistencyException(
                "checked-in reservation but room is not OCCUPIED",
                mapOf("roomId" to room.getId(), "roomStatus" to room.operationalStatus, "reservationId" to reservationId, "activeTaskId" to active?.getId()),
            )
        }

        val cancelledStayover = active?.also { cancelForCheckout(ctx, it) }

        reservation.checkOut(ctx.now)
        room.releaseAfterCheckout()
        val cleaning = tasks.save(
            HousekeepingTask.checkoutCleaning(
                propertyId = room.propertyId,
                roomId = room.getId(),
                reservationId = reservation.getId(),
                scheduledAt = ctx.now,
                estimatedDurationMinutes = housekeeping.defaultDuration(TaskType.CHECKOUT_CLEANING),
                durationSource = DurationSource.CONFIG_DEFAULT,
                createdBy = ctx.actor.staffId,
            ),
        )

        events.record(ctx, room.propertyId, "Reservation", reservation, "RESERVATION_CHECKED_OUT", mapOf("roomId" to room.getId(), "actualCheckOutAt" to ctx.now.toString()))
        recordRoomStatusChanged(ctx, room, RoomOperationalStatus.OCCUPIED, "CHECK_OUT", reservation.getId())
        events.record(
            ctx, room.propertyId, "HousekeepingTask", cleaning, "HOUSEKEEPING_TASK_CREATED",
            mapOf(
                "type" to cleaning.type.name, "origin" to cleaning.origin.name, "roomId" to room.getId(),
                "reservationId" to reservation.getId(), "estimatedDurationMinutes" to cleaning.estimatedDurationMinutes,
                "durationSource" to cleaning.durationSource.name,
            ),
        )
        return StayCommandResponse.of(ctx, reservation, room, cleaning.getId(), cancelledStayover?.getId())
    }

    private fun cancelForCheckout(ctx: CommandContext, task: HousekeepingTask) {
        val statusBefore = task.status
        val statusBeforeBlocked = task.statusBeforeBlocked
        task.cancel(CancellationCode.CHECKOUT, "체크아웃으로 시작 전 투숙 중 청소 자동 취소")
        val endedAssignment = assignments.findByTaskIdAndUnassignedAtIsNull(task.getId())
            ?.also { it.end(ctx.now, UnassignedReason.TASK_CANCELLED) }
        val closedBlockers = blockers.findByTaskIdAndResolvedAtIsNullOrderByBlockedAtAsc(task.getId())
            .onEach { it.close(ctx.now, ctx.actor.staffId, BlockerResolutionType.TASK_CANCELLED) }
        events.record(
            ctx, task.propertyId, "HousekeepingTask", task, "TASK_CANCELLED",
            mapOf(
                "cancellationCode" to CancellationCode.CHECKOUT.name,
                "statusBefore" to statusBefore.name,
                "statusBeforeBlocked" to statusBeforeBlocked?.name,
                "endedAssignmentId" to endedAssignment?.getId(),
                "closedBlockerIds" to closedBlockers.map { it.getId() },
            ),
        )
    }

    private fun stayoverInProgress(task: HousekeepingTask, requester: Actor): StayOpsException {
        val assignment = assignments.findByTaskIdAndUnassignedAtIsNull(task.getId())
        val assignee = assignment?.let { staff.findById(it.staffId).orElse(null) }
        val activeBlockers = blockers.findByTaskIdAndResolvedAtIsNullOrderByBlockedAtAsc(task.getId())
        val options = buildList {
            add("RESOLVE_BLOCKERS_AND_COMPLETE")
            if (requester.role == StaffRole.MANAGER) add("CANCEL_TASK_AS_MANAGER")
        }
        return StayOpsException(
            ErrorCode.STAYOVER_IN_PROGRESS,
            "a started stayover cleaning must be completed or cancelled by a manager before check-out",
            mapOf(
                "blockingTask" to mapOf(
                    "taskId" to task.getId(),
                    "status" to task.status.name,
                    "statusBeforeBlocked" to task.statusBeforeBlocked?.name,
                    "actualStartedAt" to task.actualStartedAt?.toString(),
                    "assignee" to assignee?.let { mapOf("staffId" to it.getId(), "displayName" to it.displayName) },
                    "activeBlockers" to activeBlockers.map {
                        mapOf(
                            "blockerId" to it.getId(),
                            "type" to it.type.name,
                            "estimatedResolutionAt" to it.estimatedResolutionAt?.toString(),
                        )
                    },
                ),
                "resolutionOptions" to options,
            ),
        )
    }

    private fun recordRoomStatusChanged(ctx: CommandContext, room: Room, from: RoomOperationalStatus, cause: String, reservationId: UUID) {
        events.record(
            ctx, room.propertyId, "Room", room, "ROOM_STATUS_CHANGED",
            mapOf("from" to from.name, "to" to room.operationalStatus.name, "cause" to cause, "reservationId" to reservationId),
        )
    }
}

data class StayCommandResponse(
    val reservationId: UUID,
    val reservationStatus: ReservationStatus,
    val reservationVersion: Long,
    val roomId: UUID,
    val roomOperationalStatus: RoomOperationalStatus,
    val roomVersion: Long,
    val checkoutCleaningTaskId: UUID?,
    val autoCancelledStayoverTaskId: UUID?,
    val occurredAt: Instant,
    val correlationId: UUID,
) {
    companion object {
        fun of(
            ctx: CommandContext,
            reservation: Reservation,
            room: Room,
            checkoutCleaningTaskId: UUID? = null,
            autoCancelledStayoverTaskId: UUID? = null,
        ) = StayCommandResponse(
            reservationId = reservation.getId(),
            reservationStatus = reservation.status,
            reservationVersion = reservation.version,
            roomId = room.getId(),
            roomOperationalStatus = room.operationalStatus,
            roomVersion = room.version,
            checkoutCleaningTaskId = checkoutCleaningTaskId,
            autoCancelledStayoverTaskId = autoCancelledStayoverTaskId,
            occurredAt = ctx.now,
            correlationId = ctx.correlationId,
        )
    }
}
