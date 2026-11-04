package com.stayops.housekeeping.application

import com.stayops.housekeeping.domain.DurationSource
import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.TaskOrigin
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.housekeeping.infrastructure.TaskAssignmentRepository
import com.stayops.housekeeping.infrastructure.TaskBlockerRepository
import com.stayops.identity.Actor
import com.stayops.room.infrastructure.RoomRepository
import com.stayops.shared.error.ErrorCode
import com.stayops.shared.error.StayOpsException
import com.stayops.shared.web.PageResponse
import com.stayops.shared.web.Paging
import com.stayops.staff.domain.StaffRole
import com.stayops.staff.infrastructure.StaffRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 작업 목록 (02 §6). 정렬은 (scheduledAt, id) keyset.
 * 역할 범위(04 §5): HOUSEKEEPER·INSPECTOR는 **본인에게 배정된 작업만** 본다 — staffId 필터는 무시하고 본인으로 고정한다.
 */
@Service
class TaskQueryService(
    private val em: EntityManager,
    private val assignments: TaskAssignmentRepository,
    private val blockers: TaskBlockerRepository,
    private val rooms: RoomRepository,
    private val staff: StaffRepository,
) {
    data class Filter(
        val statuses: Set<TaskStatus> = emptySet(),
        val types: Set<TaskType> = emptySet(),
        val staffId: UUID? = null,
        val roomId: UUID? = null,
        val scheduledFrom: Instant? = null,
        val scheduledTo: Instant? = null,
    )

    @Transactional(readOnly = true)
    fun tasks(actor: Actor, propertyId: UUID, filter: Filter, cursor: String?, limit: Int?): PageResponse<TaskListItem> {
        actor.requireProperty(propertyId)
        val size = Paging.limit(limit)
        val effective = if (actor.role == StaffRole.HOUSEKEEPER || actor.role == StaffRole.INSPECTOR) filter.copy(staffId = actor.staffId) else filter
        val after = cursor?.let(Paging::decode)?.let { (key, id) ->
            (runCatching { Instant.parse(key) }.getOrNull() ?: throw StayOpsException(ErrorCode.VALIDATION_ERROR, "cursor is invalid")) to id
        }

        val where = mutableListOf("t.propertyId = :propertyId")
        val params = mutableMapOf<String, Any>("propertyId" to propertyId)
        if (effective.statuses.isNotEmpty()) { where += "t.status in :statuses"; params["statuses"] = effective.statuses }
        if (effective.types.isNotEmpty()) { where += "t.type in :types"; params["types"] = effective.types }
        effective.roomId?.let { where += "t.roomId = :roomId"; params["roomId"] = it }
        effective.scheduledFrom?.let { where += "t.scheduledAt >= :from"; params["from"] = it }
        effective.scheduledTo?.let { where += "t.scheduledAt < :to"; params["to"] = it }
        effective.staffId?.let {
            where += "exists (select 1 from TaskAssignment a where a.taskId = t.id and a.unassignedAt is null and a.staffId = :staffId)"
            params["staffId"] = it
        }
        after?.let { (at, id) ->
            where += "(t.scheduledAt > :afterAt or (t.scheduledAt = :afterAt and t.id > :afterId))"
            params["afterAt"] = at
            params["afterId"] = id
        }
        val query = em.createQuery("select t from HousekeepingTask t where ${where.joinToString(" and ")} order by t.scheduledAt asc, t.id asc", HousekeepingTask::class.java)
        params.forEach { (k, v) -> query.setParameter(k, v) }
        val rows = query.setMaxResults(size + 1).resultList

        val page = rows.take(size)
        val ids = page.map { it.getId() }
        val assignmentByTask = assignments.findByTaskIdInAndUnassignedAtIsNull(ids).associateBy { it.taskId }
        val blockersByTask = blockers.findByTaskIdInAndResolvedAtIsNull(ids).groupBy { it.taskId }
        val roomNumbers = rooms.findAllById(page.map { it.roomId }.toSet()).associate { it.getId() to it.roomNumber }
        val staffNames = staff.findAllById(assignmentByTask.values.map { it.staffId }.toSet()).associate { it.getId() to it.displayName }

        val items = page.map { t ->
            val assignment = assignmentByTask[t.getId()]
            TaskListItem(
                taskId = t.getId(), roomId = t.roomId, roomNumber = roomNumbers[t.roomId] ?: "?", reservationId = t.reservationId,
                type = t.type, status = t.status, statusBeforeBlocked = t.statusBeforeBlocked, origin = t.origin, originReason = t.originReason,
                scheduledAt = t.scheduledAt, estimatedDurationMinutes = t.estimatedDurationMinutes, durationSource = t.durationSource,
                actualStartedAt = t.actualStartedAt, actualCompletedAt = t.actualCompletedAt, notes = t.notes,
                assignee = assignment?.let { TaskListItem.Assignee(it.getId(), it.staffId, staffNames[it.staffId]) },
                activeBlockers = blockersByTask[t.getId()].orEmpty().map { TaskListItem.Blocker(it.getId(), it.type.name, it.description, it.blockedAt, it.estimatedResolutionAt) },
                version = t.version,
            )
        }
        val next = if (rows.size > size) page.last().let { Paging.encode(it.scheduledAt.toString(), it.getId()) } else null
        return PageResponse(items, next, size)
    }
}

data class TaskListItem(
    val taskId: UUID,
    val roomId: UUID,
    val roomNumber: String,
    val reservationId: UUID?,
    val type: TaskType,
    val status: TaskStatus,
    val statusBeforeBlocked: TaskStatus?,
    val origin: TaskOrigin,
    val originReason: String?,
    val scheduledAt: Instant,
    val estimatedDurationMinutes: Int,
    val durationSource: DurationSource,
    val actualStartedAt: Instant?,
    val actualCompletedAt: Instant?,
    val notes: String?,
    val assignee: Assignee?,
    val activeBlockers: List<Blocker>,
    val version: Long,
) {
    data class Assignee(val assignmentId: UUID, val staffId: UUID, val displayName: String?)
    data class Blocker(val blockerId: UUID, val type: String, val description: String?, val blockedAt: Instant, val estimatedResolutionAt: Instant?)
}
