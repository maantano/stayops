package com.stayops.property.infrastructure

import com.stayops.property.domain.Property
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PropertyRepository : JpaRepository<Property, UUID>
