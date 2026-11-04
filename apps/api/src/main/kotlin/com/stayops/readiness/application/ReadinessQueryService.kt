package com.stayops.readiness.application

import com.stayops.housekeeping.application.HousekeepingProperties
import com.stayops.housekeeping.application.InspectionEligibility
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.housekeeping.infrastructure.HousekeepingTaskRepository
import com.stayops.housekeeping.infrastructure.TaskAssignmentRepository
import com.stayops.housekeeping.infrastructure.TaskBlockerRepository
import com.stayops.identity.Actor
import com.stayops.property.infrastructure.PropertyRepository
import com.stayops.readiness.domain.ReadinessCalculator
import com.stayops.readiness.domain.ReadinessGrade
import com.stayops.readiness.domain.ReadinessResult
import com.stayops.readiness.domain.ReadinessSettings
import com.stayops.readiness.domain.ReadinessSnapshot
import com.stayops.reservation.domain.Reservation
import com.stayops.reservation.domain.ReservationStatus
import com.stayops.reservation.infrastructure.ReservationRepository
import com.stayops.room.infrastructure.RoomRepository
import com.stayops.shared.error.NotFoundException
import com.stayops.staff.domain.StaffRole
import com.stayops.workflow.WorkflowStageService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 도착 예정 객실의 준비 위험 조회 (docs/v4.1/02 §8). DB에서 입력 스냅샷을 만들고 계산은 [ReadinessCalculator]에 맡긴다.
 * 결과는 저장하지 않는다(조회 시점 계산). 같은 스냅샷 + 시각 + 버전이면 같은 결과다.
 */
@Service
class ReadinessQueryService(
    private val properties: PropertyRepository,
    private val reservations: ReservationRepository,
    private val rooms: RoomRepository,
    private val tasks: HousekeepingTaskRepository,
    private val assignments: TaskAssignmentRepository,
    private val blockers: TaskBlockerRepository,
    private val inspections: InspectionEligibility,
    private val stages: WorkflowStageService,
    private val housekeeping: HousekeepingProperties,
    private val settings: ReadinessProperties,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun readiness(actor: Actor, propertyId: UUID): ReadinessReport {
        actor.requireProperty(propertyId)
        actor.requireRole(StaffRole.FRONT_DESK, StaffRole.MANAGER)
        val property = properties.findById(propertyId).orElseThrow { NotFoundException("property", propertyId) }
        val now = clock.instant()
        val until = now.plus(Duration.ofHours(settings.evaluationWindowHours))
        val calcSettings = ReadinessSettings(Duration.ofMinutes(settings.warningMarginMinutes), settings.calculationVersion)

        // 객실마다 가장 이른 도착 하나만 평가한다.
        val arrivals: List<Reservation> = reservations.findUpcomingArrivals(propertyId, now, until)
            .groupBy { it.roomId }
            .map { (_, list) -> list.first() }

        val results = arrivals.map { arrival ->
            ReadinessCalculator.calculate(snapshot(arrival, property.operationalBufferMinutes), calcSettings, now)
        }.sortedWith(compareBy<ReadinessResult> { GRADE_ORDER.indexOf(it.grade) }.thenBy { it.readyDeadline })

        return ReadinessReport(
            propertyId = propertyId,
            timezone = property.timezone,
            calculatedAt = now,
            evaluationWindowEnd = until,
            calculationVersion = settings.calculationVersion,
            warningMarginMinutes = settings.warningMarginMinutes,
            summary = ReadinessGrade.entries.associateWith { grade -> results.count { it.grade == grade } },
            rooms = results,
        )
    }

    private fun snapshot(arrival: Reservation, bufferMinutes: Int): ReadinessSnapshot {
        val room = rooms.findById(arrival.roomId).orElseThrow()
        val active = tasks.findFirstByRoomIdAndStatusIn(room.getId(), TaskStatus.entries.filter { it.isActive })
        val currentStay = reservations.findFirstByRoomIdAndStatus(room.getId(), ReservationStatus.CHECKED_IN)
        return ReadinessSnapshot(
            roomId = room.getId(),
            roomNumber = room.roomNumber,
            operationalStatus = room.operationalStatus,
            availabilityStatus = room.availabilityStatus,
            workflowStage = stages.stageOf(room, active).name,
            reservationId = arrival.getId(),
            scheduledCheckInAt = arrival.scheduledCheckInAt,
            operationalBufferMinutes = bufferMinutes,
            currentStayCheckOutAt = currentStay?.scheduledCheckOutAt,
            activeTask = active?.let { task ->
                ReadinessSnapshot.TaskSnapshot(
                    taskId = task.getId(), type = task.type, status = task.status, scheduledAt = task.scheduledAt,
                    estimatedDurationMinutes = task.estimatedDurationMinutes, durationSource = task.durationSource,
                    actualStartedAt = task.actualStartedAt,
                    hasAssignee = assignments.findByTaskIdAndUnassignedAtIsNull(task.getId()) != null,
                    blockers = blockers.findByTaskIdOrderByBlockedAtAsc(task.getId()).map {
                        ReadinessSnapshot.BlockerSnapshot(it.getId(), it.type, it.blockedAt, it.estimatedResolutionAt, it.resolvedAt)
                    },
                )
            },
            awaitingInspection = inspections.isAwaitingInspection(room),
            defaultDurationMinutes = TaskType.entries.associateWith { housekeeping.defaultDuration(it) },
        )
    }

    companion object {
        /** 위험한 것부터: AT_RISK, UNKNOWN(판정 불가도 확인 필요), WARNING, ON_TRACK, READY. */
        private val GRADE_ORDER = listOf(ReadinessGrade.AT_RISK, ReadinessGrade.UNKNOWN, ReadinessGrade.WARNING, ReadinessGrade.ON_TRACK, ReadinessGrade.READY)
    }
}

data class ReadinessReport(
    val propertyId: UUID,
    val timezone: String,
    val calculatedAt: Instant,
    val evaluationWindowEnd: Instant,
    val calculationVersion: String,
    val warningMarginMinutes: Long,
    val summary: Map<ReadinessGrade, Int>,
    val rooms: List<ReadinessResult>,
)
