package com.stayops.housekeeping.infrastructure

import com.stayops.housekeeping.domain.TaskBlocker
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TaskBlockerRepository : JpaRepository<TaskBlocker, UUID> {
    /** 호출 전 task 잠금을 잡고 있어야 한다 (ADR-003). */
    fun findByTaskIdAndResolvedAtIsNullOrderByBlockedAtAsc(taskId: UUID): List<TaskBlocker>

    fun findByTaskIdOrderByBlockedAtAsc(taskId: UUID): List<TaskBlocker>
}
