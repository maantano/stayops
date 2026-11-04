package com.stayops.reservation.domain

import com.stayops.shared.domain.AuditedEntity
import com.stayops.shared.error.ErrorCode
import com.stayops.shared.error.StayOpsException
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

    /**
     * CONFIRMED → CHECKED_IN. 예정 체크인 이전의 조기 체크인은 객실이 준비됐다면 허용한다(`ASSUMPTION`,
     * 허용 시간대 정책은 OPEN). 숙박 기간이 이미 끝난 예약은 체크인할 수 없다.
     */
    fun checkIn(at: Instant) {
        if (status != ReservationStatus.CONFIRMED) {
            throw StayOpsException(ErrorCode.INVALID_STATE, "reservation is $status", mapOf("currentStatus" to status.name))
        }
        if (!at.isBefore(scheduledCheckOutAt)) {
            throw StayOpsException(
                ErrorCode.INVALID_STATE,
                "stay period has already ended",
                mapOf("currentStatus" to status.name, "scheduledCheckOutAt" to scheduledCheckOutAt.toString()),
            )
        }
        status = ReservationStatus.CHECKED_IN
        actualCheckInAt = at
    }

    /** CHECKED_IN → CHECKED_OUT. 퇴실은 이 command로만 한다 (v4.1 02 §3.4). */
    fun checkOut(at: Instant) {
        if (status != ReservationStatus.CHECKED_IN) {
            throw StayOpsException(ErrorCode.INVALID_STATE, "reservation is $status", mapOf("currentStatus" to status.name))
        }
        status = ReservationStatus.CHECKED_OUT
        actualCheckOutAt = at
    }
}
