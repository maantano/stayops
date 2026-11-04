package com.stayops.housekeeping.infrastructure

import com.stayops.housekeeping.domain.HousekeepingTask
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HousekeepingTaskRepository : JpaRepository<HousekeepingTask, UUID>
