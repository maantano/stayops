package com.stayops.reservation.application

import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.infrastructure.HousekeepingTaskRepository
import com.stayops.identity.Actor
import com.stayops.reservation.domain.Reservation
import com.stayops.reservation.domain.ReservationStatus
import com.stayops.room.domain.RoomAvailabilityStatus
import com.stayops.room.infrastructure.RoomRepository
import com.stayops.shared.error.ErrorCode
import com.stayops.shared.error.StayOpsException
import com.stayops.shared.web.PageResponse
import com.stayops.shared.web.Paging
import com.stayops.staff.domain.StaffRole
import com.stayops.workflow.WorkflowStage
import com.stayops.workflow.WorkflowStageService
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 예약 목록 (02 §6). FRONT_DESK·MANAGER만. 정렬은 (scheduledCheckInAt, id) keyset. 게스트 개인정보는 없다.
 * 각 행에 객실의 현재 업무 단계·판매 상태를 함께 담아, 화면이 체크인 가능 여부와 이유를 미리 보여 줄 수 있게 한다
 * (최종 판단은 여전히 check-in command가 한다).
 */
@Service
class ReservationQueryService(
    private val em: EntityManager,
    private val rooms: RoomRepository,
    private val tasks: HousekeepingTaskRepository,
    private val stages: WorkflowStageService,
) {
    @Transactional(readOnly = true)
    fun reservations(
        actor: Actor,
        propertyId: UUID,
        statuses: Set<ReservationStatus>,
        checkInFrom: Instant?,
        checkInTo: Instant?,
        cursor: String?,
        limit: Int?,
    ): PageResponse<ReservationListItem> {
        actor.requireProperty(propertyId)
        actor.requireRole(StaffRole.FRONT_DESK, StaffRole.MANAGER)
        val size = Paging.limit(limit)
        val after = cursor?.let(Paging::decode)?.let { (key, id) ->
            (runCatching { Instant.parse(key) }.getOrNull() ?: throw StayOpsException(ErrorCode.VALIDATION_ERROR, "cursor is invalid")) to id
        }
        val where = mutableListOf("r.propertyId = :propertyId")
        val params = mutableMapOf<String, Any>("propertyId" to propertyId)
        if (statuses.isNotEmpty()) { where += "r.status in :statuses"; params["statuses"] = statuses }
        checkInFrom?.let { where += "r.scheduledCheckInAt >= :from"; params["from"] = it }
        checkInTo?.let { where += "r.scheduledCheckInAt < :to"; params["to"] = it }
        after?.let { (at, id) ->
            where += "(r.scheduledCheckInAt > :afterAt or (r.scheduledCheckInAt = :afterAt and r.id > :afterId))"
            params["afterAt"] = at
            params["afterId"] = id
        }
        val query = em.createQuery("select r from Reservation r where ${where.joinToString(" and ")} order by r.scheduledCheckInAt asc, r.id asc", Reservation::class.java)
        params.forEach { (k, v) -> query.setParameter(k, v) }
        val rows = query.setMaxResults(size + 1).resultList
        val page = rows.take(size)
        val roomById = rooms.findAllById(page.map { it.roomId }.toSet()).associateBy { it.getId() }
        val activeByRoom = tasks.findByPropertyIdAndStatusIn(propertyId, ACTIVE).associateBy { it.roomId }
        val items = page.map {
            val room = roomById.getValue(it.roomId)
            ReservationListItem(
                reservationId = it.getId(), roomId = it.roomId, roomNumber = room.roomNumber, status = it.status,
                roomWorkflowStage = stages.stageOf(room, activeByRoom[room.getId()]), roomAvailabilityStatus = room.availabilityStatus,
                scheduledCheckInAt = it.scheduledCheckInAt, scheduledCheckOutAt = it.scheduledCheckOutAt,
                actualCheckInAt = it.actualCheckInAt, actualCheckOutAt = it.actualCheckOutAt, guestCount = it.guestCount, version = it.version,
            )
        }
        val next = if (rows.size > size) page.last().let { Paging.encode(it.scheduledCheckInAt.toString(), it.getId()) } else null
        return PageResponse(items, next, size)
    }

    private companion object {
        val ACTIVE = TaskStatus.entries.filter { it.isActive }
    }
}

data class ReservationListItem(
    val reservationId: UUID,
    val roomId: UUID,
    val roomNumber: String,
    val status: ReservationStatus,
    /** 객실의 현재 업무 단계. 체크인은 READY일 때만 된다. */
    val roomWorkflowStage: WorkflowStage,
    /** 객실 판매 상태. OUT_OF_SERVICE면 체크인할 수 없다. */
    val roomAvailabilityStatus: RoomAvailabilityStatus,
    val scheduledCheckInAt: Instant,
    val scheduledCheckOutAt: Instant,
    val actualCheckInAt: Instant?,
    val actualCheckOutAt: Instant?,
    val guestCount: Int,
    val version: Long,
)
