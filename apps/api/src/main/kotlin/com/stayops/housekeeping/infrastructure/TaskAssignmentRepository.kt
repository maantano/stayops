package com.stayops.housekeeping.infrastructure

import com.stayops.housekeeping.domain.TaskAssignment
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TaskAssignmentRepository : JpaRepository<TaskAssignment, UUID>
