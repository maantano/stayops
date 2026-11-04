package com.stayops.reservation.infrastructure

import com.stayops.reservation.domain.Reservation
import com.stayops.reservation.domain.ReservationStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface ReservationRepository : JpaRepository<Reservation, UUID> {
    fun findByRoomIdOrderByScheduledCheckInAtAsc(roomId: UUID): List<Reservation>

    fun findFirstByRoomIdAndStatus(roomId: UUID, status: ReservationStatus): Reservation?

    /** 평가 대상 도착: 확정 예약 중 [until] 이전 체크인 예정이고 아직 숙박 기간이 끝나지 않은 것 (지연 도착 포함). */
    @Query(
        """
        select r from Reservation r
        where r.propertyId = :propertyId and r.status = com.stayops.reservation.domain.ReservationStatus.CONFIRMED
          and r.scheduledCheckInAt < :until and r.scheduledCheckOutAt > :now
        order by r.scheduledCheckInAt asc
        """,
    )
    fun findUpcomingArrivals(propertyId: UUID, now: Instant, until: Instant): List<Reservation>
}
