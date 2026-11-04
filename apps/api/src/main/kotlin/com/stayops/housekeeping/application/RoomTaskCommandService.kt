package com.stayops.housekeeping.application

import com.stayops.housekeeping.domain.DurationSource
import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.TaskAssignment
import com.stayops.housekeeping.domain.TaskType
import com.stayops.housekeeping.infrastructure.HousekeepingTaskRepository
import com.stayops.housekeeping.infrastructure.TaskAssignmentRepository
import com.stayops.operations.application.EventRecorder
import com.stayops.room.domain.RoomAvailabilityStatus
import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.shared.command.CommandContext
import com.stayops.shared.error.ErrorCode
import com.stayops.shared.error.StateInconsistencyException
import com.stayops.shared.error.StayOpsException
import com.stayops.staff.domain.StaffRole
import com.stayops.workflow.lock.RoomLockScope
import com.stayops.workflow.lock.RoomWorkflowLocks
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 객실 기준으로 task를 만드는 command: 투숙 중 청소 요청, 관리자 재정비 (docs/v4.1/02 §4). */
@Service
class RoomTaskCommandService(
    private val locks: RoomWorkflowLocks,
    private val events: EventRecorder,
    private val tasks: HousekeepingTaskRepository,
    private val assignments: TaskAssignmentRepository,
    private val assignees: AssigneePolicy,
    private val inspections: InspectionEligibility,
    private val housekeeping: HousekeepingProperties,
) {

    enum class ReworkKind { CLEANING, INSPECTION }

    // --------------------------------------------------------------- stayover

    @Transactional(propagation = Propagation.MANDATORY)
    fun createStayover(ctx: CommandContext, roomId: UUID, scheduledAt: Instant?, durationMinutes: Int?, notes: String?): TaskCommandResponse {
        ctx.actor.requireRole(StaffRole.FRONT_DESK, StaffRole.MANAGER)
        val scope = locks.lockRoom(ctx.actor.propertyId, roomId)
        val reservation = scope.checkedInReservation()
        requireNoActiveTask(scope)
        val room = scope.room
        if (room.availabilityStatus != RoomAvailabilityStatus.AVAILABLE) {
            throw StayOpsException(ErrorCode.ROOM_NOT_AVAILABLE, "room is ${room.availabilityStatus}", mapOf("availabilityStatus" to room.availabilityStatus.name))
        }
        if (room.operationalStatus != RoomOperationalStatus.OCCUPIED) {
            throw StayOpsException(ErrorCode.INVALID_STATE, "stayover cleaning requires an OCCUPIED room", mapOf("roomOperationalStatus" to room.operationalStatus.name))
        }
        if (reservation == null) {
            throw StateInconsistencyException("OCCUPIED room without a CHECKED_IN reservation", mapOf("roomId" to roomId))
        }
        val at = validateScheduledAt(scheduledAt, ctx.now)
        if (!at.isBefore(reservation.scheduledCheckOutAt)) {
            throw StayOpsException(ErrorCode.VALIDATION_ERROR, "scheduledAt must be before the reservation's scheduled check-out")
        }

        val task = tasks.save(
            HousekeepingTask.stayover(
                propertyId = room.propertyId, roomId = room.getId(), reservationId = reservation.getId(), scheduledAt = at,
                estimatedDurationMinutes = durationMinutes ?: housekeeping.defaultDuration(TaskType.STAYOVER_CLEANING),
                durationSource = if (durationMinutes != null) DurationSource.USER_INPUT else DurationSource.CONFIG_DEFAULT,
                createdBy = ctx.actor.staffId, notes = notes?.trim()?.ifEmpty { null },
            ),
        )
        recordCreated(ctx, task)
        return TaskCommandResponse.of(ctx, task, room)
    }

    // ----------------------------------------------------------------- rework

    @Transactional(propagation = Propagation.MANDATORY)
    fun createRework(
        ctx: CommandContext,
        roomId: UUID,
        kind: ReworkKind,
        reason: String,
        durationMinutes: Int?,
        scheduledAt: Instant?,
        assigneeStaffId: UUID?,
    ): TaskCommandResponse {
        ctx.actor.requireRole(StaffRole.MANAGER)
        val scope = locks.lockRoom(ctx.actor.propertyId, roomId)
        val checkedIn = scope.checkedInReservation()
        requireNoActiveTask(scope)
        val room = scope.room
        val at = validateScheduledAt(scheduledAt, ctx.now)

        val (type, inspectedCleaningId) = when (kind) {
            ReworkKind.CLEANING -> {
                if (room.operationalStatus != RoomOperationalStatus.VACANT_DIRTY) {
                    throw StayOpsException(ErrorCode.INVALID_STATE, "rework cleaning requires a VACANT_DIRTY room", mapOf("roomOperationalStatus" to room.operationalStatus.name))
                }
                TaskType.RE_CLEANING to null
            }
            ReworkKind.INSPECTION -> {
                val cleaning = inspections.uninspectedCompletedCleaning(room.getId())
                if (room.operationalStatus != RoomOperationalStatus.VACANT_DIRTY || cleaning == null) {
                    throw StayOpsException(
                        ErrorCode.INVALID_STATE,
                        "the room has no completed cleaning awaiting inspection",
                        mapOf("roomOperationalStatus" to room.operationalStatus.name, "reason" to "NOT_AWAITING_INSPECTION"),
                    )
                }
                TaskType.INSPECTION to cleaning.getId()
            }
        }
        if (checkedIn != null) {
            throw StateInconsistencyException("VACANT_DIRTY room with a CHECKED_IN reservation", mapOf("roomId" to roomId, "reservationId" to checkedIn.getId()))
        }

        val task = HousekeepingTask.managerRework(
            propertyId = room.propertyId, roomId = room.getId(), type = type, reason = reason.trim(), inspectedCleaningId = inspectedCleaningId,
            scheduledAt = at, estimatedDurationMinutes = durationMinutes ?: housekeeping.defaultDuration(type),
            durationSource = if (durationMinutes != null) DurationSource.USER_INPUT else DurationSource.CONFIG_DEFAULT,
            createdBy = ctx.actor.staffId,
        )
        // 담당자를 함께 지정하면 같은 transaction에서 배정 규칙을 검증하고, 실패하면 task도 만들지 않는다.
        val assignee = assigneeStaffId?.let {
            when (val result = assignees.evaluate(it, task, at)) {
                is AssigneePolicy.Result.Invalid -> throw StayOpsException(result.code, "assignee is not valid for this task", mapOf("reason" to result.reason))
                is AssigneePolicy.Result.Valid -> result
            }
        }

        tasks.save(task)
        recordCreated(ctx, task, mapOf("originReason" to task.originReason))
        var assignmentId: UUID? = null
        if (assignee != null) {
            val assignment = assignments.save(TaskAssignment(task.getId(), assignee.staff.getId(), ctx.now, ctx.actor.staffId))
            task.markAssigned()
            events.record(ctx, task.propertyId, AGGREGATE, task, "TASK_ASSIGNED", mapOf("assignmentId" to assignment.getId(), "staffId" to assignee.staff.getId()))
            assignmentId = assignment.getId()
        }
        return TaskCommandResponse.of(ctx, task, room, assignmentId = assignmentId)
    }

    // ---------------------------------------------------------------- helpers

    private fun requireNoActiveTask(scope: RoomLockScope) {
        val active = scope.activeTask() ?: return
        throw StayOpsException(
            ErrorCode.ACTIVE_WORKFLOW_EXISTS,
            "room already has an active task",
            mapOf("existingTaskId" to active.getId(), "existingTaskType" to active.type.name, "existingTaskStatus" to active.status.name),
        )
    }

    /** 생략하면 지금. 클라이언트 시계 차이를 감안해 5분까지 과거를 허용한다 (`ASSUMPTION`). */
    private fun validateScheduledAt(scheduledAt: Instant?, now: Instant): Instant {
        if (scheduledAt == null) return now
        if (scheduledAt.isBefore(now.minus(PAST_TOLERANCE))) {
            throw StayOpsException(ErrorCode.VALIDATION_ERROR, "scheduledAt must not be in the past")
        }
        return scheduledAt
    }

    private fun recordCreated(ctx: CommandContext, task: HousekeepingTask, extra: Map<String, Any?> = emptyMap()) {
        events.record(
            ctx, task.propertyId, AGGREGATE, task, "HOUSEKEEPING_TASK_CREATED",
            mapOf(
                "type" to task.type.name, "origin" to task.origin.name, "roomId" to task.roomId, "reservationId" to task.reservationId,
                "sourceTaskId" to task.sourceTaskId, "scheduledAt" to task.scheduledAt.toString(),
                "estimatedDurationMinutes" to task.estimatedDurationMinutes, "durationSource" to task.durationSource.name,
            ) + extra,
        )
    }

    companion object {
        private const val AGGREGATE = "HousekeepingTask"
        private val PAST_TOLERANCE: Duration = Duration.ofMinutes(5)
    }
}
