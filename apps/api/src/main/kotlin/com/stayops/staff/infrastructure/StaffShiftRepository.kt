package com.stayops.staff.infrastructure

import com.stayops.staff.domain.StaffShift
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StaffShiftRepository : JpaRepository<StaffShift, UUID>
