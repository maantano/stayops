package com.stayops.room.application

import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.housekeeping.infrastructure.HousekeepingTaskRepository
import com.stayops.housekeeping.infrastructure.TaskAssignmentRepository
import com.stayops.identity.Actor
import com.stayops.room.domain.RoomAvailabilityStatus
import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.room.infrastructure.RoomRepository
import com.stayops.shared.error.NotFoundException
import com.stayops.workflow.WorkflowStage
import com.stayops.workflow.WorkflowStageService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 객실 상세 조회 (02 §6 Query 중 room detail). 목록·대시보드·타임라인은 P8에서 확장한다.
 * MVP에서는 같은 property의 모든 역할이 조회할 수 있다(`ASSUMPTION`, 04 §5의 세분화는 P8).
 */
@Service
class RoomQueryService(
    private val rooms: RoomRepository,
    private val tasks: HousekeepingTaskRepository,
    private val assignments: TaskAssignmentRepository,
    private val stages: WorkflowStageService,
) {
    @Transactional(readOnly = true)
    fun room(actor: Actor, propertyId: UUID, roomId: UUID): RoomView {
        actor.requireProperty(propertyId)
        val room = rooms.findById(roomId).orElse(null)?.takeIf { it.propertyId == propertyId }
            ?: throw NotFoundException("room", roomId)
        val active = tasks.findFirstByRoomIdAndStatusIn(room.getId(), TaskStatus.entries.filter { it.isActive })
        val assignment = active?.let { assignments.findByTaskIdAndUnassignedAtIsNull(it.getId()) }
        val alerts = buildList {
            if (active != null && active.isStarted && assignment == null) add(ALERT_WORK_INTERRUPTED)
        }
        return RoomView(
            roomId = room.getId(), roomNumber = room.roomNumber, roomType = room.roomType, floor = room.floor,
            operationalStatus = room.operationalStatus, availabilityStatus = room.availabilityStatus,
            availabilityReason = room.availabilityReason, workflowStage = stages.stageOf(room, active),
            activeTask = active?.let {
                RoomView.ActiveTask(
                    taskId = it.getId(), type = it.type, status = it.status, statusBeforeBlocked = it.statusBeforeBlocked,
                    actualStartedAt = it.actualStartedAt, assigneeStaffId = assignment?.staffId,
                )
            },
            alerts = alerts, version = room.version,
        )
    }

    companion object {
        /** 시작된 작업에 담당자가 없음 (차단 해제 시 담당자 무효로 PENDING 복귀, OPEN-5). */
        const val ALERT_WORK_INTERRUPTED = "WORK_INTERRUPTED_NO_ASSIGNEE"
    }
}

data class RoomView(
    val roomId: UUID,
    val roomNumber: String,
    val roomType: String,
    val floor: Int,
    val operationalStatus: RoomOperationalStatus,
    val availabilityStatus: RoomAvailabilityStatus,
    val availabilityReason: String?,
    val workflowStage: WorkflowStage,
    val activeTask: ActiveTask?,
    val alerts: List<String>,
    val version: Long,
) {
    data class ActiveTask(
        val taskId: UUID,
        val type: TaskType,
        val status: TaskStatus,
        val statusBeforeBlocked: TaskStatus?,
        val actualStartedAt: Instant?,
        val assigneeStaffId: UUID?,
    )
}
