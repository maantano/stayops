package com.stayops.schema

import com.stayops.housekeeping.domain.DurationSource
import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.TaskOrigin
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.property.domain.Property
import com.stayops.reservation.domain.Reservation
import com.stayops.reservation.domain.ReservationStatus
import com.stayops.room.domain.Room
import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.staff.domain.Staff
import com.stayops.staff.domain.StaffRole
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** 스키마 테스트용 합성 데이터 생성기. 값은 모두 임의의 테스트 가정이다. */
class SchemaFixtures(private val em: TestEntityManager) {

    val t0: Instant = Instant.parse("2026-11-05T00:00:00Z")
    val actor: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a001")

    fun at(hours: Long): Instant = t0.plus(Duration.ofHours(hours))

    fun property(name: String = "Synthetic Hotel"): Property =
        em.persistAndFlush(
            Property(
                name = name,
                timezone = ZoneId.of("Asia/Seoul"),
                defaultCheckInTime = LocalTime.of(15, 0),
                defaultCheckOutTime = LocalTime.of(11, 0),
                operationalBufferMinutes = 30,
            ),
        )

    fun room(
        property: Property,
        number: String = "101",
        status: RoomOperationalStatus = RoomOperationalStatus.VACANT_DIRTY,
    ): Room = em.persistAndFlush(
        Room(
            propertyId = property.getId(),
            roomNumber = number,
            roomType = "STANDARD",
            floor = 1,
            operationalStatus = status,
        ),
    )

    fun reservation(
        room: Room,
        fromHour: Long = 0,
        toHour: Long = 24,
        status: ReservationStatus = ReservationStatus.CONFIRMED,
    ): Reservation = em.persistAndFlush(newReservation(room, fromHour, toHour, status))

    fun newReservation(
        room: Room,
        fromHour: Long,
        toHour: Long,
        status: ReservationStatus = ReservationStatus.CONFIRMED,
        propertyId: UUID = room.propertyId,
    ): Reservation = Reservation(
        propertyId = propertyId,
        roomId = room.getId(),
        scheduledCheckInAt = at(fromHour),
        scheduledCheckOutAt = at(toHour),
        guestCount = 2,
        status = status,
        actualCheckInAt = if (status == ReservationStatus.CHECKED_IN || status == ReservationStatus.CHECKED_OUT) at(fromHour) else null,
        actualCheckOutAt = if (status == ReservationStatus.CHECKED_OUT) at(toHour) else null,
    )

    fun staff(property: Property, role: StaffRole = StaffRole.HOUSEKEEPER, name: String = "HK-01"): Staff =
        em.persistAndFlush(Staff(propertyId = property.getId(), displayName = name, role = role))

    /** 기본값은 유효한 PENDING RE_CLEANING(관리자 재정비) task. */
    fun newTask(
        room: Room,
        type: TaskType = TaskType.RE_CLEANING,
        origin: TaskOrigin = TaskOrigin.MANAGER_REWORK,
        originReason: String? = "재청소 필요",
        status: TaskStatus = TaskStatus.PENDING,
        statusBeforeBlocked: TaskStatus? = null,
        reservationId: UUID? = null,
        sourceTaskId: UUID? = null,
        actualStartedAt: Instant? = null,
        actualCompletedAt: Instant? = null,
        propertyId: UUID = room.propertyId,
    ): HousekeepingTask = HousekeepingTask(
        propertyId = propertyId,
        roomId = room.getId(),
        type = type,
        origin = origin,
        originReason = originReason,
        scheduledAt = at(1),
        estimatedDurationMinutes = 40,
        durationSource = DurationSource.CONFIG_DEFAULT,
        createdBy = actor,
        reservationId = reservationId,
        sourceTaskId = sourceTaskId,
        status = status,
        statusBeforeBlocked = statusBeforeBlocked,
        actualStartedAt = actualStartedAt,
        actualCompletedAt = actualCompletedAt,
    )

    fun task(room: Room): HousekeepingTask = em.persistAndFlush(newTask(room))

    fun completedCleaning(room: Room): HousekeepingTask = em.persistAndFlush(
        newTask(room, status = TaskStatus.COMPLETED, actualStartedAt = at(1), actualCompletedAt = at(2)),
    )
}
