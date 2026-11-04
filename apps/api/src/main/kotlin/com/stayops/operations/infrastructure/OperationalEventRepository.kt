package com.stayops.operations.infrastructure

import com.stayops.operations.domain.OperationalEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OperationalEventRepository : JpaRepository<OperationalEvent, UUID>
