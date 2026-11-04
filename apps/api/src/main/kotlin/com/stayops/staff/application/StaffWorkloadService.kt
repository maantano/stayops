package com.stayops.staff.application

import com.stayops.housekeeping.infrastructure.HousekeepingTaskRepository
import com.stayops.housekeeping.infrastructure.TaskAssignmentRepository
import com.stayops.identity.Actor
import com.stayops.property.infrastructure.PropertyRepository
import com.stayops.shared.error.NotFoundException
import com.stayops.staff.domain.StaffRole
import com.stayops.staff.infrastructure.StaffRepository
import com.stayops.staff.infrastructure.StaffShiftRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 직원별 업무량 (01 §6 Workload ratio, 02 §6 staff-workloads). FRONT_DESK·MANAGER만.
 *
 * workloadRatio = 배정된 active task의 예상 소요 합 / 그 날 근무 가능 분(근무 − 휴게).
 * 이동·준비 시간은 반영하지 않는다(표시용 note). 근무가 없으면 ratio는 null + `NO_SHIFT`.
 */
@Service
class StaffWorkloadService(
    private val properties: PropertyRepository,
    private val staff: StaffRepository,
    private val shifts: StaffShiftRepository,
    private val assignments: TaskAssignmentRepository,
    private val tasks: HousekeepingTaskRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun workloads(actor: Actor, propertyId: UUID, date: LocalDate?): StaffWorkloadReport {
        actor.requireProperty(propertyId)
        actor.requireRole(StaffRole.FRONT_DESK, StaffRole.MANAGER)
        val property = properties.findById(propertyId).orElseThrow { NotFoundException("property", propertyId) }
        val zone = property.zoneId
        val businessDate = date ?: LocalDate.now(clock.withZone(zone))
        val dayStart = businessDate.atStartOfDay(zone).toInstant()
        val dayEnd = businessDate.plusDays(1).atStartOfDay(zone).toInstant()

        val workers = staff.findByPropertyIdAndActiveTrue(propertyId).filter { it.role == StaffRole.HOUSEKEEPER || it.role == StaffRole.INSPECTOR }
        val workerIds = workers.map { it.getId() }
        val shiftsByStaff = shifts.findOverlapping(workerIds, dayStart, dayEnd).groupBy { it.staffId }
        val activeAssignments = assignments.findByStaffIdInAndUnassignedAtIsNull(workerIds)
        val taskById = tasks.findAllById(activeAssignments.map { it.taskId }.toSet()).filter { it.status.isActive }.associateBy { it.getId() }

        val rows = workers.map { member ->
            val memberShifts = shiftsByStaff[member.getId()].orEmpty()
            // 업무일 범위로 잘라 근무 가능 분을 계산한다(휴게는 근무 전체에 걸린 값이므로 그대로 차감).
            val availableMinutes = memberShifts.sumOf { s ->
                val from = maxOf(s.startsAt, dayStart)
                val to = minOf(s.endsAt, dayEnd)
                (Duration.between(from, to).toMinutes() - s.breakMinutes).coerceAtLeast(0)
            }
            val assigned = activeAssignments.filter { it.staffId == member.getId() }.mapNotNull { taskById[it.taskId] }
            val assignedMinutes = assigned.sumOf { it.estimatedDurationMinutes.toLong() }
            StaffWorkloadReport.Row(
                staffId = member.getId(), displayName = member.displayName, role = member.role,
                shifts = memberShifts.map { StaffWorkloadReport.Shift(it.startsAt, it.endsAt, it.breakMinutes) },
                availableMinutes = availableMinutes, activeTaskCount = assigned.size, assignedEstimatedMinutes = assignedMinutes,
                workloadRatio = if (availableMinutes > 0) assignedMinutes.toDouble() / availableMinutes else null,
                flags = buildList {
                    if (memberShifts.isEmpty()) add("NO_SHIFT")
                    if (availableMinutes in 1 until assignedMinutes) add("OVER_CAPACITY")
                },
            )
        }.sortedWith(compareBy<StaffWorkloadReport.Row> { it.role }.thenBy { it.displayName })

        return StaffWorkloadReport(propertyId, property.timezone, businessDate, rows, "이동·준비 시간 미반영. 배정된 active task의 예상 소요 합 기준 (ASSUMPTION)")
    }
}

data class StaffWorkloadReport(
    val propertyId: UUID,
    val timezone: String,
    val businessDate: LocalDate,
    val staff: List<Row>,
    val note: String,
) {
    data class Row(
        val staffId: UUID,
        val displayName: String,
        val role: StaffRole,
        val shifts: List<Shift>,
        val availableMinutes: Long,
        val activeTaskCount: Int,
        val assignedEstimatedMinutes: Long,
        val workloadRatio: Double?,
        val flags: List<String>,
    )

    data class Shift(val startsAt: Instant, val endsAt: Instant, val breakMinutes: Int)
}
