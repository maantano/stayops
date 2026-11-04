package com.stayops.room.application

import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.housekeeping.infrastructure.HousekeepingTaskRepository
import com.stayops.housekeeping.infrastructure.TaskAssignmentRepository
import com.stayops.identity.Actor
import com.stayops.room.domain.Room
import com.stayops.room.domain.RoomAvailabilityStatus
import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.room.infrastructure.RoomRepository
import com.stayops.shared.error.ErrorCode
import com.stayops.shared.error.NotFoundException
import com.stayops.shared.error.StayOpsException
import com.stayops.shared.web.PageResponse
import com.stayops.shared.web.Paging
import com.stayops.workflow.WorkflowStage
import com.stayops.workflow.WorkflowStageService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * 객실 조회 (02 §6): 상세, 목록, 이벤트 타임라인.
 * 객실 정보는 하우스키핑·검사 업무에 필요하므로 같은 property의 모든 역할이 조회할 수 있다 (04 §5 "필요 범위").
 */
@Service
class RoomQueryService(
    private val rooms: RoomRepository,
    private val tasks: HousekeepingTaskRepository,
    private val assignments: TaskAssignmentRepository,
    private val stages: WorkflowStageService,
    private val jdbc: JdbcTemplate,
    private val jsonMapper: JsonMapper,
) {
    @Transactional(readOnly = true)
    fun room(actor: Actor, propertyId: UUID, roomId: UUID): RoomView {
        actor.requireProperty(propertyId)
        val room = rooms.findById(roomId).orElse(null)?.takeIf { it.propertyId == propertyId }
            ?: throw NotFoundException("room", roomId)
        val active = tasks.findFirstByRoomIdAndStatusIn(room.getId(), ACTIVE)
        return view(room, active, active?.let { assignments.findByTaskIdAndUnassignedAtIsNull(it.getId())?.staffId })
    }

    /**
     * 객실 목록. property당 객실 수가 작다는 전제(MVP, 합성 데이터 80실)로 메모리에서 단계를 계산·필터링한 뒤
     * (roomNumber, id) keyset으로 자른다. 객실 수가 커지면 workflowStage를 조회 모델로 저장하는 방식으로 바꾼다.
     */
    @Transactional(readOnly = true)
    fun rooms(actor: Actor, propertyId: UUID, stage: WorkflowStage?, floor: Int?, cursor: String?, limit: Int?): PageResponse<RoomView> {
        actor.requireProperty(propertyId)
        val size = Paging.limit(limit)
        val all = rooms.findByPropertyId(propertyId).filter { floor == null || it.floor == floor }
        val activeByRoom = tasks.findByPropertyIdAndStatusIn(propertyId, ACTIVE).associateBy { it.roomId }
        val assigneeByTask = assignments.findByTaskIdInAndUnassignedAtIsNull(activeByRoom.values.map { it.getId() })
            .associate { it.taskId to it.staffId }
        val views = all.map { room -> activeByRoom[room.getId()].let { view(room, it, it?.let { t -> assigneeByTask[t.getId()] }) } }
            .filter { stage == null || it.workflowStage == stage }
            .sortedWith(compareBy<RoomView> { it.roomNumber }.thenBy { it.roomId.toString() })
        return Paging.slice(views, cursor, size, { it.roomNumber }, { it.roomId })
    }

    /** 객실·그 객실의 task·예약에 대한 이벤트를 최신순으로 (02 §6 timeline, H4 사후 재구성). */
    @Transactional(readOnly = true)
    fun timeline(actor: Actor, propertyId: UUID, roomId: UUID, cursor: String?, limit: Int?): PageResponse<TimelineEntry> {
        actor.requireProperty(propertyId)
        rooms.findById(roomId).orElse(null)?.takeIf { it.propertyId == propertyId } ?: throw NotFoundException("room", roomId)
        val size = Paging.limit(limit)
        val after = cursor?.let(Paging::decode)?.let { (key, id) ->
            (runCatching { Instant.parse(key) }.getOrNull() ?: throw StayOpsException(ErrorCode.VALIDATION_ERROR, "cursor is invalid")) to id
        }
        val sql = buildString {
            append(
                """
                select e.id, e.occurred_at, e.event_type, e.aggregate_type, e.aggregate_id, e.actor_type, e.actor_id,
                       s.display_name actor_name, e.correlation_id, e.payload::text payload
                from operational_event e
                left join staff s on s.id = e.actor_id
                where e.property_id = ?
                  and (e.aggregate_id = ?
                       or e.aggregate_id in (select t.id from housekeeping_task t where t.room_id = ?)
                       or e.aggregate_id in (select r.id from reservation r where r.room_id = ?))
                """.trimIndent(),
            )
            if (after != null) append(" and (e.occurred_at < ? or (e.occurred_at = ? and e.id < ?))")
            append(" order by e.occurred_at desc, e.id desc limit ?")
        }
        val args = mutableListOf<Any>(propertyId, roomId, roomId, roomId)
        after?.let { (at, id) -> args.addAll(listOf(Timestamp.from(at), Timestamp.from(at), id)) }
        args += size + 1
        val rows = jdbc.query(sql, { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            TimelineEntry(
                eventId = rs.getObject("id", UUID::class.java),
                occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                eventType = rs.getString("event_type"),
                aggregateType = rs.getString("aggregate_type"),
                aggregateId = rs.getObject("aggregate_id", UUID::class.java),
                actorType = rs.getString("actor_type"),
                actorId = rs.getObject("actor_id", UUID::class.java),
                actorDisplayName = rs.getString("actor_name"),
                correlationId = rs.getObject("correlation_id", UUID::class.java),
                payload = jsonMapper.readValue(rs.getString("payload"), Map::class.java) as Map<String, Any?>,
            )
        }, *args.toTypedArray())
        val page = rows.take(size)
        val next = if (rows.size > size) page.last().let { Paging.encode(it.occurredAt.toString(), it.eventId) } else null
        return PageResponse(page, next, size)
    }

    private fun view(room: Room, active: HousekeepingTask?, assigneeStaffId: UUID?): RoomView {
        val alerts = buildList { if (active != null && active.isStarted && assigneeStaffId == null) add(ALERT_WORK_INTERRUPTED) }
        return RoomView(
            roomId = room.getId(), roomNumber = room.roomNumber, roomType = room.roomType, floor = room.floor,
            operationalStatus = room.operationalStatus, availabilityStatus = room.availabilityStatus,
            availabilityReason = room.availabilityReason, workflowStage = stages.stageOf(room, active),
            activeTask = active?.let {
                RoomView.ActiveTask(
                    taskId = it.getId(), type = it.type, status = it.status, statusBeforeBlocked = it.statusBeforeBlocked,
                    actualStartedAt = it.actualStartedAt, assigneeStaffId = assigneeStaffId,
                )
            },
            alerts = alerts, version = room.version,
        )
    }

    companion object {
        /** 시작된 작업에 담당자가 없음 (차단 해제 시 담당자 무효로 PENDING 복귀, OPEN-5). */
        const val ALERT_WORK_INTERRUPTED = "WORK_INTERRUPTED_NO_ASSIGNEE"
        private val ACTIVE = TaskStatus.entries.filter { it.isActive }
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

data class TimelineEntry(
    val eventId: UUID,
    val occurredAt: Instant,
    val eventType: String,
    val aggregateType: String,
    val aggregateId: UUID,
    val actorType: String,
    val actorId: UUID?,
    val actorDisplayName: String?,
    val correlationId: UUID,
    val payload: Map<String, Any?>,
)
