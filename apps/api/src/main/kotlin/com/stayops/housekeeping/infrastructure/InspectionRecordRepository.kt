package com.stayops.housekeeping.infrastructure

import com.stayops.housekeeping.domain.InspectionRecord
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface InspectionRecordRepository : JpaRepository<InspectionRecord, UUID> {
    fun findByTaskId(taskId: UUID): InspectionRecord?
}
