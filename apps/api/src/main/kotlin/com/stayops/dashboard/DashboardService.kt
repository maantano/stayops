package com.stayops.dashboard

import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.infrastructure.HousekeepingTaskRepository
import com.stayops.identity.Actor
import com.stayops.property.infrastructure.PropertyRepository
import com.stayops.readiness.application.ReadinessQueryService
import com.stayops.readiness.domain.ReadinessGrade
import com.stayops.reservation.domain.Reservation
import com.stayops.reservation.domain.ReservationStatus
import com.stayops.room.application.RoomQueryService
import com.stayops.room.domain.RoomAvailabilityStatus
import com.stayops.shared.error.NotFoundException
import com.stayops.shared.web.Paging
import com.stayops.staff.domain.StaffRole
import com.stayops.workflow.WorkflowStage
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 운영 대시보드 (docs/v4.1/03 §3 Dashboard). FRONT_DESK·MANAGER만.
 * "오늘"은 property timezone의 업무일이다 (02 §1-7). 집계는 조회 시점 값이며 상세 목록과 시각 차이가 있을 수 있다.
 */
@Service
class DashboardService(
    private val properties: PropertyRepository,
    private val roomQueries: RoomQueryService,
    private val readiness: ReadinessQueryService,
    private val tasks: HousekeepingTaskRepository,
    private val em: EntityManager,
    private val clock: Clock,
    @Value("\${stayops.demo-mode}") private val demoMode: Boolean,
) {
    @Transactional(readOnly = true)
    fun dashboard(actor: Actor, propertyId: UUID): Dashboard {
        actor.requireProperty(propertyId)
        actor.requireRole(StaffRole.FRONT_DESK, StaffRole.MANAGER)
        val property = properties.findById(propertyId).orElseThrow { NotFoundException("property", propertyId) }
        val zone = property.zoneId
        val now = clock.instant()
        val today = LocalDate.now(clock.withZone(zone))
        val dayStart = today.atStartOfDay(zone).toInstant()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant()

        val rooms = allRooms(actor, propertyId)
        val reservations = em.createQuery(
            """
            select r from Reservation r where r.propertyId = :p and (
              (r.scheduledCheckInAt >= :start and r.scheduledCheckInAt < :end) or
              (r.scheduledCheckOutAt >= :start and r.scheduledCheckOutAt < :end) or
              (r.actualCheckInAt >= :start and r.actualCheckInAt < :end) or
              (r.actualCheckOutAt >= :start and r.actualCheckOutAt < :end))
            """.trimIndent(),
            Reservation::class.java,
        ).setParameter("p", propertyId).setParameter("start", dayStart).setParameter("end", dayEnd).resultList
        fun Instant?.today() = this != null && !isBefore(dayStart) && isBefore(dayEnd)

        val activeTasks = tasks.findByPropertyIdAndStatusIn(propertyId, TaskStatus.entries.filter { it.isActive })
        val report = readiness.readiness(actor, propertyId)

        return Dashboard(
            propertyId = propertyId,
            timezone = property.timezone,
            businessDate = today,
            generatedAt = now,
            syntheticData = demoMode,
            rooms = Dashboard.Rooms(
                total = rooms.size,
                byStage = WorkflowStage.entries.associateWith { stage -> rooms.count { it.workflowStage == stage } },
                byAvailability = RoomAvailabilityStatus.entries.associateWith { a -> rooms.count { it.availabilityStatus == a } },
                interruptedWork = rooms.filter { RoomQueryService.ALERT_WORK_INTERRUPTED in it.alerts }.map { it.roomId },
            ),
            stays = Dashboard.Stays(
                expectedArrivals = reservations.count { it.status == ReservationStatus.CONFIRMED && it.scheduledCheckInAt.today() },
                checkedInToday = reservations.count { it.actualCheckInAt.today() },
                expectedDepartures = reservations.count { it.status == ReservationStatus.CHECKED_IN && it.scheduledCheckOutAt.today() },
                checkedOutToday = reservations.count { it.actualCheckOutAt.today() },
            ),
            tasks = TaskStatus.entries.filter { it.isActive }.associateWith { s -> activeTasks.count { it.status == s } },
            readiness = Dashboard.ReadinessSummary(
                summary = report.summary,
                calculationVersion = report.calculationVersion,
                topRisks = report.rooms.filter { it.grade == ReadinessGrade.AT_RISK || it.grade == ReadinessGrade.UNKNOWN || it.grade == ReadinessGrade.WARNING }
                    .take(TOP_RISKS)
                    .map { r ->
                        Dashboard.RiskItem(
                            roomId = r.roomId, roomNumber = r.roomNumber, grade = r.grade, riskMinutes = r.riskMinutes,
                            readyDeadline = r.readyDeadline, expectedReadyAt = r.expectedReadyAt,
                            topReasons = (r.missingInputs.map { "MISSING:$it" } + r.contributions.sortedByDescending { it.seconds }.map { it.type.name }).distinct().take(3),
                        )
                    },
            ),
        )
    }

    private fun allRooms(actor: Actor, propertyId: UUID) = buildList {
        var cursor: String? = null
        do {
            val page = roomQueries.rooms(actor, propertyId, null, null, cursor, Paging.MAX_LIMIT)
            addAll(page.items)
            cursor = page.nextCursor
        } while (cursor != null)
    }

    companion object {
        private const val TOP_RISKS = 5
    }
}

data class Dashboard(
    val propertyId: UUID,
    val timezone: String,
    val businessDate: LocalDate,
    val generatedAt: Instant,
    /** 합성 데이터 데모 표시 (01 §8). */
    val syntheticData: Boolean,
    val rooms: Rooms,
    val stays: Stays,
    val tasks: Map<TaskStatus, Int>,
    val readiness: ReadinessSummary,
) {
    data class Rooms(
        val total: Int,
        val byStage: Map<WorkflowStage, Int>,
        val byAvailability: Map<RoomAvailabilityStatus, Int>,
        val interruptedWork: List<UUID>,
    )

    data class Stays(val expectedArrivals: Int, val checkedInToday: Int, val expectedDepartures: Int, val checkedOutToday: Int)

    data class ReadinessSummary(val summary: Map<ReadinessGrade, Int>, val calculationVersion: String, val topRisks: List<RiskItem>)

    data class RiskItem(
        val roomId: UUID,
        val roomNumber: String,
        val grade: ReadinessGrade,
        val riskMinutes: Long?,
        val readyDeadline: Instant,
        val expectedReadyAt: Instant?,
        /** 원인 상위 3개: 누락 입력(MISSING:…) 우선, 그다음 시간 기여가 큰 순. */
        val topReasons: List<String>,
    )
}
