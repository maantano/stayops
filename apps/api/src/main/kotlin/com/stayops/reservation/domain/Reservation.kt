package com.stayops.reservation.domain

import com.stayops.shared.domain.AuditedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class ReservationStatus {
    CONFIRMED,
    CHECKED_IN,
    CHECKED_OUT,
    CANCELLED,
    NO_SHOW,
}

/**
 * 예약. 구간은 `[scheduledCheckInAt, scheduledCheckOutAt)`.
 * 게스트 개인정보 필드는 두지 않는다 (합성 데이터만 사용, 02 §12).
 */
@Entity
@Table(name = "reservation")
class Reservation(
    propertyId: UUID,
    roomId: UUID,
    scheduledCheckInAt: Instant,
    scheduledCheckOutAt: Instant,
    guestCount: Int,
    status: ReservationStatus = ReservationStatus.CONFIRMED,
    actualCheckInAt: Instant? = null,
    actualCheckOutAt: Instant? = null,
    id: UUID = UUID.randomUUID(),
) : AuditedEntity(id) {

    @Column(name = "property_id", nullable = false, updatable = false)
    val propertyId: UUID = propertyId

    @Column(name = "room_id", nullable = false)
    var roomId: UUID = roomId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: ReservationStatus = status
        protected set

    @Column(name = "scheduled_check_in_at", nullable = false)
    var scheduledCheckInAt: Instant = scheduledCheckInAt
        protected set

    @Column(name = "scheduled_check_out_at", nullable = false)
    var scheduledCheckOutAt: Instant = scheduledCheckOutAt
        protected set

    @Column(name = "actual_check_in_at")
    var actualCheckInAt: Instant? = actualCheckInAt
        protected set

    @Column(name = "actual_check_out_at")
    var actualCheckOutAt: Instant? = actualCheckOutAt
        protected set

    @Column(name = "guest_count", nullable = false)
    var guestCount: Int = guestCount
        protected set
}
