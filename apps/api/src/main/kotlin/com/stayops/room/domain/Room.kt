package com.stayops.room.domain

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

/** 객실의 업무 단계 (02 §2 Room). availability와 독립된 축이다. */
enum class RoomOperationalStatus {
    VACANT_DIRTY,
    CLEANING,
    INSPECTING,
    READY,
    OCCUPIED,
    STAYOVER_CLEANING,
}

/** 판매·신규 투숙 가능 여부. operational status와 합치지 않는다. */
enum class RoomAvailabilityStatus {
    AVAILABLE,
    OUT_OF_SERVICE,
}

@Entity
@Table(name = "room")
class Room(
    propertyId: UUID,
    roomNumber: String,
    roomType: String,
    floor: Int,
    operationalStatus: RoomOperationalStatus,
    availabilityStatus: RoomAvailabilityStatus = RoomAvailabilityStatus.AVAILABLE,
    availabilityReason: String? = null,
    id: UUID = UUID.randomUUID(),
) : AuditedEntity(id) {

    @Column(name = "property_id", nullable = false, updatable = false)
    val propertyId: UUID = propertyId

    @Column(name = "room_number", nullable = false, length = 20)
    var roomNumber: String = roomNumber
        protected set

    @Column(name = "room_type", nullable = false, length = 30)
    var roomType: String = roomType
        protected set

    @Column(name = "floor", nullable = false)
    var floor: Int = floor
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "operational_status", nullable = false, length = 30)
    var operationalStatus: RoomOperationalStatus = operationalStatus
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "availability_status", nullable = false, length = 30)
    var availabilityStatus: RoomAvailabilityStatus = availabilityStatus
        protected set

    @Column(name = "availability_reason", length = 500)
    var availabilityReason: String? = availabilityReason
        protected set

    @Column(name = "availability_changed_at")
    var availabilityChangedAt: Instant? = null
        protected set

    /** 체크인: READY이면서 AVAILABLE이어야 한다 (v4.1 02 §2 Room). OOS를 먼저 확인한다. */
    fun admitGuest() {
        if (availabilityStatus != RoomAvailabilityStatus.AVAILABLE) {
            throw StayOpsException(
                ErrorCode.ROOM_NOT_AVAILABLE,
                "room is $availabilityStatus",
                mapOf("availabilityStatus" to availabilityStatus.name, "availabilityReason" to availabilityReason),
            )
        }
        if (operationalStatus != RoomOperationalStatus.READY) {
            throw StayOpsException(
                ErrorCode.INVALID_STATE,
                "room is not ready",
                mapOf("roomOperationalStatus" to operationalStatus.name),
            )
        }
        operationalStatus = RoomOperationalStatus.OCCUPIED
    }

    /**
     * 작업 workflow에 따른 상태 전이 (02 §3.3 matrix). 호출 전 서비스가 [from]과 실제 상태를 비교해
     * 다르면 STATE_INCONSISTENT로 거부해야 한다. 여기서는 마지막 방어로만 검사한다.
     */
    fun changeWorkflowStatus(from: RoomOperationalStatus, to: RoomOperationalStatus) {
        check(operationalStatus == from) { "room must be $from but was $operationalStatus" }
        operationalStatus = to
    }

    /**
     * 판매·신규 투숙 가능 여부 변경 (02 §5). operationalStatus는 바꾸지 않는다 — OOS 해제만으로 READY가 되지 않는다.
     * 같은 상태로의 변경이면 false를 반환하고 아무것도 바꾸지 않는다.
     */
    fun changeAvailability(to: RoomAvailabilityStatus, reason: String, at: Instant): Boolean {
        if (availabilityStatus == to) return false
        availabilityStatus = to
        availabilityReason = if (to == RoomAvailabilityStatus.OUT_OF_SERVICE) reason else null
        availabilityChangedAt = at
        return true
    }

    /** 체크아웃: OCCUPIED → VACANT_DIRTY. 호출 전 서비스가 상태 일관성을 확인한다. */
    fun releaseAfterCheckout() {
        check(operationalStatus == RoomOperationalStatus.OCCUPIED) { "room must be OCCUPIED but was $operationalStatus" }
        operationalStatus = RoomOperationalStatus.VACANT_DIRTY
    }
}
