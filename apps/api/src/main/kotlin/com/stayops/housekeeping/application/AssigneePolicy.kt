package com.stayops.housekeeping.application

import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.TaskRoomRules
import com.stayops.shared.error.ErrorCode
import com.stayops.staff.domain.Staff
import com.stayops.staff.domain.StaffShift
import com.stayops.staff.infrastructure.StaffRepository
import com.stayops.staff.infrastructure.StaffShiftRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** 담당자 유효성: 활성, 같은 property, task type별 역할, [at]을 포함하는 근무 (02 §4 Task assign·start, §3.2 복귀). */
@Component
class AssigneePolicy(
    private val staff: StaffRepository,
    private val shifts: StaffShiftRepository,
) {
    sealed interface Result {
        data class Valid(val staff: Staff, val shift: StaffShift) : Result
        data class Invalid(val code: ErrorCode, val reason: String) : Result
    }

    fun evaluate(staffId: UUID, task: HousekeepingTask, at: Instant): Result {
        val member = staff.findById(staffId).orElse(null)
            ?: return Result.Invalid(ErrorCode.ASSIGNMENT_INVALID, "STAFF_NOT_FOUND")
        if (member.propertyId != task.propertyId) return Result.Invalid(ErrorCode.ASSIGNMENT_INVALID, "STAFF_NOT_FOUND")
        if (!member.active) return Result.Invalid(ErrorCode.ASSIGNMENT_INVALID, "STAFF_INACTIVE")
        val required = TaskRoomRules.assigneeRole(task.type)
        if (member.role != required) return Result.Invalid(ErrorCode.ASSIGNMENT_INVALID, "ROLE_MISMATCH:required=$required")
        val shift = shifts.findCovering(staffId, at)
            ?: return Result.Invalid(ErrorCode.SHIFT_CONFLICT, "NO_SHIFT_AT:$at")
        return Result.Valid(member, shift)
    }
}
