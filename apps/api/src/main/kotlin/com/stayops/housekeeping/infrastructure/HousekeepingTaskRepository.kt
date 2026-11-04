package com.stayops.housekeeping.infrastructure

import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.TaskType
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HousekeepingTaskRepository : JpaRepository<HousekeepingTask, UUID> {
    fun findByRoomIdOrderByCreatedAtAsc(roomId: UUID): List<HousekeepingTask>

    fun findByReservationIdAndType(reservationId: UUID, type: TaskType): HousekeepingTask?
}
