package com.stayops.staff.infrastructure

import com.stayops.staff.domain.Staff
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StaffRepository : JpaRepository<Staff, UUID>
