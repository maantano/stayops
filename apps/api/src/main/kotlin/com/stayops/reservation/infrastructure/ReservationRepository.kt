package com.stayops.reservation.infrastructure

import com.stayops.reservation.domain.Reservation
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReservationRepository : JpaRepository<Reservation, UUID> {
    fun findByRoomIdOrderByScheduledCheckInAtAsc(roomId: UUID): List<Reservation>
}
