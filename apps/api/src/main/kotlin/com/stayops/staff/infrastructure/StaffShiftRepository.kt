package com.stayops.staff.infrastructure

import com.stayops.staff.domain.StaffShift
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface StaffShiftRepository : JpaRepository<StaffShift, UUID> {
    /** [at]을 포함하는 근무 (`[startsAt, endsAt)`). DB 제약상 직원당 겹치는 근무가 없으므로 최대 1개. */
    @Query("select s from StaffShift s where s.staffId = :staffId and s.startsAt <= :at and s.endsAt > :at")
    fun findCovering(staffId: UUID, at: Instant): StaffShift?
}
