package com.stayops.housekeeping.infrastructure

import com.stayops.housekeeping.domain.TaskAssignment
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TaskAssignmentRepository : JpaRepository<TaskAssignment, UUID> {
    /** DB 제약상 task당 최대 1개. 호출 전 task 잠금을 잡고 있어야 한다 (ADR-003). */
    fun findByTaskIdAndUnassignedAtIsNull(taskId: UUID): TaskAssignment?

    fun findByTaskIdOrderByAssignedAtAsc(taskId: UUID): List<TaskAssignment>

    /** 조회용 일괄 로딩. */
    fun findByTaskIdInAndUnassignedAtIsNull(taskIds: Collection<UUID>): List<TaskAssignment>

    fun findByStaffIdInAndUnassignedAtIsNull(staffIds: Collection<UUID>): List<TaskAssignment>
}
