package com.stayops.housekeeping.application

import com.stayops.housekeeping.domain.BlockerResolutionType
import com.stayops.housekeeping.domain.BlockerType
import com.stayops.housekeeping.domain.CancellationCode
import com.stayops.housekeeping.domain.DurationSource
import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.TaskAssignment
import com.stayops.housekeeping.domain.TaskBlocker
import com.stayops.housekeeping.domain.TaskRoomRules
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.housekeeping.domain.UnassignedReason
import com.stayops.housekeeping.infrastructure.HousekeepingTaskRepository
import com.stayops.housekeeping.infrastructure.TaskAssignmentRepository
import com.stayops.housekeeping.infrastructure.TaskBlockerRepository
import com.stayops.operations.application.EventRecorder
import com.stayops.reservation.domain.ReservationStatus
import com.stayops.room.domain.Room
import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.shared.command.CommandContext
import com.stayops.shared.error.ErrorCode
import com.stayops.shared.error.NotFoundException
import com.stayops.shared.error.StateInconsistencyException
import com.stayops.shared.error.StayOpsException
import com.stayops.staff.domain.StaffRole
import com.stayops.workflow.lock.RoomWorkflowLocks
import com.stayops.workflow.lock.TaskLock
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 작업 lifecycle command (docs/v4.1/02 §3.2·§3.3·§4, 04 §5 RBAC).
 *
 * - "시작 전/후"는 항상 `actualStartedAt`으로 판정한다 (02 §2.8).
 * - Room 상태가 matrix와 맞지 않으면 보정하지 않고 STATE_INCONSISTENT.
 * - partial unique index(활성 assignment 1개, 객실당 active task 1개)는 문장 단위로 검사되고 Hibernate는
 *   insert를 update보다 먼저 실행하므로, "기존 행 종료 → 새 행 생성" 순서가 필요한 곳은 명시적으로 flush한다.
 */
@Service
class TaskCommandService(
    private val locks: RoomWorkflowLocks,
    private val events: EventRecorder,
    private val tasks: HousekeepingTaskRepository,
    private val assignments: TaskAssignmentRepository,
    private val blockers: TaskBlockerRepository,
    private val assignees: AssigneePolicy,
    private val inspections: InspectionEligibility,
    private val housekeeping: HousekeepingProperties,
    private val em: EntityManager,
) {

    // ------------------------------------------------------------------ assign

    @Transactional(propagation = Propagation.MANDATORY)
    fun assign(ctx: CommandContext, taskId: UUID, staffId: UUID): TaskCommandResponse {
        ctx.actor.requireRole(StaffRole.MANAGER)
        val lock = locks.lockForTaskCommand(ctx.actor.propertyId, taskId)
        val task = lock.task
        if (task.status != TaskStatus.PENDING && task.status != TaskStatus.ASSIGNED) {
            throw StayOpsException(ErrorCode.INVALID_STATE, "task cannot be assigned while ${task.status}", mapOf("currentStatus" to task.status.name))
        }

        val windowStart = maxOf(task.scheduledAt, ctx.now)
        val valid = when (val result = assignees.evaluate(staffId, task, windowStart)) {
            is AssigneePolicy.Result.Invalid -> throw StayOpsException(result.code, "assignee is not valid for this task", mapOf("reason" to result.reason))
            is AssigneePolicy.Result.Valid -> result
        }
        val warnings = buildList {
            if (windowStart.plusSeconds(task.estimatedDurationMinutes * 60L).isAfter(valid.shift.endsAt)) add("EXPECTED_END_AFTER_SHIFT_END")
        }

        val current = assignments.findByTaskIdAndUnassignedAtIsNull(task.getId())
        if (current?.staffId == staffId) {
            return TaskCommandResponse.of(ctx, task, lock.room, assignmentId = current.getId(), warnings = warnings)
        }
        current?.end(ctx.now, UnassignedReason.REASSIGNED)
        em.flush()
        val assignment = assignments.save(TaskAssignment(task.getId(), staffId, ctx.now, ctx.actor.staffId))
        task.markAssigned()

        events.record(
            ctx, task.propertyId, AGGREGATE, task, "TASK_ASSIGNED",
            mapOf("assignmentId" to assignment.getId(), "staffId" to staffId, "previousStaffId" to current?.staffId, "warnings" to warnings),
        )
        return TaskCommandResponse.of(ctx, task, lock.room, assignmentId = assignment.getId(), warnings = warnings)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun unassign(ctx: CommandContext, taskId: UUID, assignmentId: UUID): TaskCommandResponse {
        ctx.actor.requireRole(StaffRole.MANAGER)
        val lock = locks.lockForTaskCommand(ctx.actor.propertyId, taskId)
        val task = lock.task
        val current = assignments.findByTaskIdAndUnassignedAtIsNull(task.getId())
        if (current == null || current.getId() != assignmentId) {
            throw StayOpsException(ErrorCode.INVALID_STATE, "assignment $assignmentId is not the active assignment of this task", mapOf("currentStatus" to task.status.name))
        }
        task.markUnassigned()
        current.end(ctx.now, UnassignedReason.MANUAL_UNASSIGN)
        events.record(ctx, task.propertyId, AGGREGATE, task, "TASK_UNASSIGNED", mapOf("assignmentId" to assignmentId, "staffId" to current.staffId))
        return TaskCommandResponse.of(ctx, task, lock.room)
    }

    // ------------------------------------------------------------------- start

    /** 02 §4 Task start 판정 표 (1번 Idempotency-Key 재전송은 executor가 처리). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun start(ctx: CommandContext, taskId: UUID): TaskCommandResponse {
        val lock = locks.lockForTaskCommand(ctx.actor.propertyId, taskId)
        val task = lock.task
        val room = lock.room
        val assignment = assignments.findByTaskIdAndUnassignedAtIsNull(task.getId())
        val isAssignee = assignment?.staffId == ctx.actor.staffId

        when (task.status) {
            TaskStatus.COMPLETED, TaskStatus.CANCELLED -> // 2
                throw StayOpsException(ErrorCode.INVALID_STATE, "task is ${task.status}", mapOf("currentStatus" to task.status.name))
            TaskStatus.IN_PROGRESS -> { // 3, 4
                if (isAssignee || ctx.actor.role == StaffRole.MANAGER) {
                    return TaskCommandResponse.of(ctx, task, room, alreadyStarted = true)
                }
                throw StayOpsException(ErrorCode.FORBIDDEN, "only the assignee can start this task")
            }
            TaskStatus.BLOCKED -> throw activeBlockerExists(task) // 5
            TaskStatus.PENDING -> // 6
                throw StayOpsException(ErrorCode.ASSIGNMENT_INVALID, "task has no assignee", mapOf("currentStatus" to task.status.name))
            TaskStatus.ASSIGNED -> Unit
        }

        // 7: 담당자 본인만 시작한다 (MANAGER 대리 실행 금지, OPEN-4). 담당자는 지금 유효해야 한다.
        if (!isAssignee) throw StayOpsException(ErrorCode.FORBIDDEN, "only the assignee can start this task")
        val check = assignees.evaluate(assignment!!.staffId, task, ctx.now)
        if (check is AssigneePolicy.Result.Invalid) {
            throw StayOpsException(check.code, "assignee cannot start this task now", mapOf("reason" to check.reason))
        }

        // 8: 같은 객실의 다른 active task (DB 제약상 정상 데이터에서는 불가능)
        val other = lock.scope.activeTask()
        if (other != null && other.getId() != task.getId()) {
            throw StayOpsException(ErrorCode.ACTIVE_WORKFLOW_EXISTS, "room has another active task", existing(other))
        }

        return if (!task.isStarted) firstStart(ctx, lock) else resume(ctx, lock)
    }

    private fun firstStart(ctx: CommandContext, lock: TaskLock): TaskCommandResponse {
        val task = lock.task
        val room = lock.room
        val expected = TaskRoomRules.roomBeforeStart(task.type)
        val preconditionOk = when (task.type) {
            TaskType.INSPECTION -> inspections.canStartInspection(room, task)
            TaskType.STAYOVER_CLEANING ->
                room.operationalStatus == expected &&
                    lock.checkedInReservation?.status == ReservationStatus.CHECKED_IN &&
                    lock.checkedInReservation.getId() == task.reservationId
            TaskType.CHECKOUT_CLEANING, TaskType.RE_CLEANING -> room.operationalStatus == expected
        }
        if (!preconditionOk) throw inconsistent("first start precondition failed", task, room, expected)

        val to = TaskRoomRules.roomWhileInProgress(task.type)
        task.start(ctx.now)
        room.changeWorkflowStatus(expected, to)
        events.record(ctx, task.propertyId, AGGREGATE, task, "TASK_STARTED", mapOf("roomId" to room.getId()))
        recordRoomStatus(ctx, room, expected, "TASK_STARTED", task)
        return TaskCommandResponse.of(ctx, task, room, alreadyStarted = false)
    }

    private fun resume(ctx: CommandContext, lock: TaskLock): TaskCommandResponse {
        val task = lock.task
        val room = lock.room
        val expected = TaskRoomRules.roomWhileInProgress(task.type)
        if (room.operationalStatus != expected) throw inconsistent("resume precondition failed", task, room, expected)
        task.start(ctx.now)
        events.record(ctx, task.propertyId, AGGREGATE, task, "TASK_RESUMED", mapOf("staffId" to ctx.actor.staffId, "firstStartedAt" to task.actualStartedAt.toString()))
        return TaskCommandResponse.of(ctx, task, room, alreadyStarted = false)
    }

    // ---------------------------------------------------------------- blockers

    @Transactional(propagation = Propagation.MANDATORY)
    fun addBlocker(ctx: CommandContext, taskId: UUID, type: BlockerType, description: String?, estimatedResolutionAt: Instant?): TaskCommandResponse {
        val lock = locks.lockForTaskCommand(ctx.actor.propertyId, taskId)
        val task = lock.task
        requireManagerOrAssignee(ctx, task)
        if (!task.status.isActive) {
            throw StayOpsException(ErrorCode.INVALID_STATE, "task is ${task.status}", mapOf("currentStatus" to task.status.name))
        }
        if (type == BlockerType.OTHER && description.isNullOrBlank()) {
            throw StayOpsException(ErrorCode.VALIDATION_ERROR, "description is required for OTHER blockers")
        }
        if (estimatedResolutionAt != null && !estimatedResolutionAt.isAfter(ctx.now)) {
            throw StayOpsException(ErrorCode.VALIDATION_ERROR, "estimatedResolutionAt must be in the future")
        }

        val blocker = blockers.save(TaskBlocker(task.getId(), type, ctx.now, description?.trim(), estimatedResolutionAt))
        task.block()
        events.record(
            ctx, task.propertyId, AGGREGATE, task, "BLOCKER_ADDED",
            mapOf("blockerId" to blocker.getId(), "type" to type.name, "statusBeforeBlocked" to task.statusBeforeBlocked?.name, "estimatedResolutionAt" to estimatedResolutionAt?.toString()),
        )
        return TaskCommandResponse.of(ctx, task, lock.room, blockerId = blocker.getId())
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun resolveBlocker(ctx: CommandContext, taskId: UUID, blockerId: UUID, note: String?): TaskCommandResponse {
        val lock = locks.lockForTaskCommand(ctx.actor.propertyId, taskId)
        val task = lock.task
        requireManagerOrAssignee(ctx, task)
        val blocker = blockers.findById(blockerId).orElse(null)
        if (blocker == null || blocker.taskId != task.getId()) throw NotFoundException("blocker", blockerId)
        if (!blocker.isActive) {
            throw StayOpsException(ErrorCode.INVALID_STATE, "blocker is already resolved", mapOf("currentStatus" to task.status.name))
        }
        if (task.status != TaskStatus.BLOCKED) {
            throw StateInconsistencyException("active blocker on a non-BLOCKED task", mapOf("taskId" to task.getId(), "status" to task.status, "blockerId" to blockerId))
        }

        blocker.close(ctx.now, ctx.actor.staffId, BlockerResolutionType.RESOLVED, note?.trim())
        em.flush()
        val remaining = blockers.findByTaskIdAndResolvedAtIsNullOrderByBlockedAtAsc(task.getId())
        var assigneeInvalidReason: String? = null
        if (remaining.isEmpty()) {
            var target = checkNotNull(task.statusBeforeBlocked) { "BLOCKED task without statusBeforeBlocked" }
            if (target == TaskStatus.ASSIGNED || target == TaskStatus.IN_PROGRESS) {
                val assignment = assignments.findByTaskIdAndUnassignedAtIsNull(task.getId())
                // ASSIGNED 복귀는 배정과 같은 기준(작업 예정 시각), IN_PROGRESS 복귀는 지금 근무 중이어야 한다.
                val at = if (target == TaskStatus.ASSIGNED) maxOf(task.scheduledAt, ctx.now) else ctx.now
                val check = assignment?.let { assignees.evaluate(it.staffId, task, at) }
                if (assignment == null || check is AssigneePolicy.Result.Invalid) {
                    assigneeInvalidReason = (check as? AssigneePolicy.Result.Invalid)?.reason ?: "NO_ACTIVE_ASSIGNMENT"
                    assignment?.end(ctx.now, UnassignedReason.ASSIGNEE_INVALID_ON_RESUME, assigneeInvalidReason)
                    target = TaskStatus.PENDING
                }
            }
            task.unblock(target)
        }

        events.record(
            ctx, task.propertyId, AGGREGATE, task, "BLOCKER_RESOLVED",
            mapOf(
                "blockerId" to blockerId,
                "remainingActiveBlockers" to remaining.size,
                "restoredStatus" to if (remaining.isEmpty()) task.status.name else null,
                "assigneeInvalidReason" to assigneeInvalidReason,
            ),
        )
        val warnings = listOfNotNull(assigneeInvalidReason?.let { "ASSIGNEE_INVALID_ON_RESUME" })
        return TaskCommandResponse.of(ctx, task, lock.room, blockerId = blockerId, warnings = warnings)
    }

    // ---------------------------------------------------------------- complete

    @Transactional(propagation = Propagation.MANDATORY)
    fun complete(ctx: CommandContext, taskId: UUID): TaskCommandResponse {
        val lock = locks.lockForTaskCommand(ctx.actor.propertyId, taskId)
        val task = lock.task
        val room = lock.room
        if (task.type == TaskType.INSPECTION) {
            throw StayOpsException(ErrorCode.INVALID_STATE, "inspection tasks complete through inspection-result", mapOf("currentStatus" to task.status.name))
        }
        if (task.status == TaskStatus.BLOCKED) throw activeBlockerExists(task)
        if (task.status != TaskStatus.IN_PROGRESS) {
            throw StayOpsException(ErrorCode.INVALID_STATE, "task cannot be completed while ${task.status}", mapOf("currentStatus" to task.status.name))
        }
        val assignment = assignments.findByTaskIdAndUnassignedAtIsNull(task.getId())
        if (assignment?.staffId != ctx.actor.staffId) throw StayOpsException(ErrorCode.FORBIDDEN, "only the assignee can complete this task")

        val from = TaskRoomRules.roomWhileInProgress(task.type)
        if (room.operationalStatus != from) throw inconsistent("complete precondition failed", task, room, from)

        task.complete(ctx.now)
        assignment.end(ctx.now, UnassignedReason.TASK_COMPLETED)
        room.changeWorkflowStatus(from, TaskRoomRules.roomAfterComplete(task.type))
        events.record(ctx, task.propertyId, AGGREGATE, task, "TASK_COMPLETED", mapOf("roomId" to room.getId()))
        recordRoomStatus(ctx, room, from, "TASK_COMPLETED", task)

        var inspectionId: UUID? = null
        if (task.type.isCleaning) {
            em.flush() // 객실당 active task 1개 index: 완료 반영 후 검사 task 생성
            val inspection = tasks.save(
                HousekeepingTask.inspectionOf(
                    cleaning = task,
                    scheduledAt = ctx.now,
                    estimatedDurationMinutes = housekeeping.defaultDuration(TaskType.INSPECTION),
                    durationSource = DurationSource.CONFIG_DEFAULT,
                    createdBy = ctx.actor.staffId,
                ),
            )
            events.record(
                ctx, task.propertyId, AGGREGATE, inspection, "HOUSEKEEPING_TASK_CREATED",
                mapOf("type" to inspection.type.name, "origin" to inspection.origin.name, "sourceTaskId" to task.getId(), "roomId" to room.getId()),
            )
            inspectionId = inspection.getId()
        }
        return TaskCommandResponse.of(ctx, task, room, createdTaskId = inspectionId)
    }

    // ------------------------------------------------------------------ cancel

    @Transactional(propagation = Propagation.MANDATORY)
    fun cancel(ctx: CommandContext, taskId: UUID, reason: String): TaskCommandResponse {
        val lock = locks.lockForTaskCommand(ctx.actor.propertyId, taskId)
        val task = lock.task
        val room = lock.room
        if (!task.status.isActive) {
            throw StayOpsException(ErrorCode.INVALID_STATE, "task is ${task.status}", mapOf("currentStatus" to task.status.name))
        }
        // 취소 권한은 status가 아니라 시작 여부로 판정한다 (v4.1 N3).
        if (task.isStarted) {
            ctx.actor.requireRole(StaffRole.MANAGER)
        } else if (!(ctx.actor.role == StaffRole.MANAGER || (ctx.actor.role == StaffRole.FRONT_DESK && task.type == TaskType.STAYOVER_CLEANING))) {
            throw StayOpsException(ErrorCode.FORBIDDEN, "role ${ctx.actor.role} cannot cancel this task")
        }

        val roomFrom = room.operationalStatus
        if (task.isStarted) {
            val expected = TaskRoomRules.roomWhileInProgress(task.type)
            if (room.operationalStatus != expected) throw inconsistent("cancel-after-start precondition failed", task, room, expected)
        }
        val statusBefore = task.status
        task.cancel(CancellationCode.MANUAL, reason.trim())
        val ended = assignments.findByTaskIdAndUnassignedAtIsNull(task.getId())?.also { it.end(ctx.now, UnassignedReason.TASK_CANCELLED) }
        val closed = blockers.findByTaskIdAndResolvedAtIsNullOrderByBlockedAtAsc(task.getId())
            .onEach { it.close(ctx.now, ctx.actor.staffId, BlockerResolutionType.TASK_CANCELLED) }
        if (task.isStarted) room.changeWorkflowStatus(roomFrom, TaskRoomRules.roomAfterCancelAfterStart(task.type))

        events.record(
            ctx, task.propertyId, AGGREGATE, task, "TASK_CANCELLED",
            mapOf(
                "cancellationCode" to CancellationCode.MANUAL.name, "reason" to reason.trim(), "statusBefore" to statusBefore.name,
                "started" to task.isStarted, "endedAssignmentId" to ended?.getId(), "closedBlockerIds" to closed.map { it.getId() },
            ),
        )
        if (room.operationalStatus != roomFrom) recordRoomStatus(ctx, room, roomFrom, "TASK_CANCELLED", task)
        return TaskCommandResponse.of(ctx, task, room)
    }

    // ------------------------------------------------------------------ helpers

    private fun requireManagerOrAssignee(ctx: CommandContext, task: HousekeepingTask) {
        if (ctx.actor.role == StaffRole.MANAGER) return
        val assignment = assignments.findByTaskIdAndUnassignedAtIsNull(task.getId())
        if (assignment?.staffId != ctx.actor.staffId) throw StayOpsException(ErrorCode.FORBIDDEN, "only the assignee or a manager can change blockers")
    }

    private fun activeBlockerExists(task: HousekeepingTask): StayOpsException {
        val active = blockers.findByTaskIdAndResolvedAtIsNullOrderByBlockedAtAsc(task.getId())
        return StayOpsException(
            ErrorCode.ACTIVE_BLOCKER_EXISTS,
            "task has unresolved blockers",
            mapOf(
                "currentStatus" to task.status.name,
                "activeBlockers" to active.map { mapOf("blockerId" to it.getId(), "type" to it.type.name, "estimatedResolutionAt" to it.estimatedResolutionAt?.toString()) },
            ),
        )
    }

    private fun existing(task: HousekeepingTask) =
        mapOf("existingTaskId" to task.getId(), "existingTaskType" to task.type.name, "existingTaskStatus" to task.status.name)

    private fun inconsistent(what: String, task: HousekeepingTask, room: Room, expected: RoomOperationalStatus) =
        StateInconsistencyException(
            what,
            mapOf(
                "taskId" to task.getId(), "taskType" to task.type, "taskStatus" to task.status, "actualStartedAt" to task.actualStartedAt,
                "roomId" to room.getId(), "expectedRoomStatus" to expected, "actualRoomStatus" to room.operationalStatus,
            ),
        )

    private fun recordRoomStatus(ctx: CommandContext, room: Room, from: RoomOperationalStatus, cause: String, task: HousekeepingTask) {
        events.record(
            ctx, room.propertyId, "Room", room, "ROOM_STATUS_CHANGED",
            mapOf("from" to from.name, "to" to room.operationalStatus.name, "cause" to cause, "taskId" to task.getId()),
        )
    }

    companion object {
        private const val AGGREGATE = "HousekeepingTask"
    }
}

data class TaskCommandResponse(
    val taskId: UUID,
    val taskType: TaskType,
    val taskStatus: TaskStatus,
    val statusBeforeBlocked: TaskStatus?,
    val actualStartedAt: Instant?,
    val taskVersion: Long,
    val roomId: UUID,
    val roomOperationalStatus: RoomOperationalStatus,
    val roomVersion: Long,
    val alreadyStarted: Boolean?,
    val assignmentId: UUID?,
    val blockerId: UUID?,
    val createdTaskId: UUID?,
    val warnings: List<String>,
    val occurredAt: Instant,
    val correlationId: UUID,
) {
    companion object {
        fun of(
            ctx: CommandContext,
            task: HousekeepingTask,
            room: Room,
            alreadyStarted: Boolean? = null,
            assignmentId: UUID? = null,
            blockerId: UUID? = null,
            createdTaskId: UUID? = null,
            warnings: List<String> = emptyList(),
        ) = TaskCommandResponse(
            taskId = task.getId(), taskType = task.type, taskStatus = task.status, statusBeforeBlocked = task.statusBeforeBlocked,
            actualStartedAt = task.actualStartedAt, taskVersion = task.version, roomId = room.getId(),
            roomOperationalStatus = room.operationalStatus, roomVersion = room.version, alreadyStarted = alreadyStarted,
            assignmentId = assignmentId, blockerId = blockerId, createdTaskId = createdTaskId, warnings = warnings,
            occurredAt = ctx.now, correlationId = ctx.correlationId,
        )
    }
}
