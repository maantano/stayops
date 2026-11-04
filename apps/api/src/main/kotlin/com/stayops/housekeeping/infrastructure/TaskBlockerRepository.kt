package com.stayops.housekeeping.infrastructure

import com.stayops.housekeeping.domain.TaskBlocker
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TaskBlockerRepository : JpaRepository<TaskBlocker, UUID>
