package com.stayops.support

import com.stayops.housekeeping.domain.BlockerType
import com.stayops.housekeeping.domain.DurationSource
import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.TaskAssignment
import com.stayops.housekeeping.domain.TaskBlocker
import com.stayops.housekeeping.domain.TaskOrigin
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.housekeeping.domain.UnassignedReason
import com.stayops.housekeeping.infrastructure.HousekeepingTaskRepository
import com.stayops.housekeeping.infrastructure.TaskAssignmentRepository
import com.stayops.housekeeping.infrastructure.TaskBlockerRepository
import com.stayops.property.domain.Property
import com.stayops.property.infrastructure.PropertyRepository
import com.stayops.reservation.domain.Reservation
import com.stayops.reservation.domain.ReservationStatus
import com.stayops.reservation.infrastructure.ReservationRepository
import com.stayops.room.domain.Room
import com.stayops.room.domain.RoomAvailabilityStatus
import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.room.infrastructure.RoomRepository
import com.stayops.staff.domain.Staff
import com.stayops.staff.domain.StaffRole
import com.stayops.staff.infrastructure.StaffRepository
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * 통합 테스트용 합성 데이터 구성기. 테스트마다 [newHotel]로 독립된 property를 만든다.
 * 값은 모두 임의의 테스트 가정이며, 저장은 commit된다(실제 HTTP 요청이 별도 transaction에서 읽으므로).
 */
@Component
class StayWorld(
    private val properties: PropertyRepository,
    private val rooms: RoomRepository,
    private val staffs: StaffRepository,
    private val reservations: ReservationRepository,
    private val tasks: HousekeepingTaskRepository,
    private val assignments: TaskAssignmentRepository,
    private val blockers: TaskBlockerRepository,
) {
    inner class Hotel {
        val property: Property = properties.save(
            Property("Synthetic Hotel ${UUID.randomUUID()}", ZoneId.of("Asia/Seoul"), LocalTime.of(15, 0), LocalTime.of(11, 0), 30),
        )
        val manager: Staff = staff(StaffRole.MANAGER, "MGR-01")
        val frontDesk: Staff = staff(StaffRole.FRONT_DESK, "FD-01")
        val housekeeper: Staff = staff(StaffRole.HOUSEKEEPER, "HK-01")
        private var roomSeq = 100

        fun staff(role: StaffRole, name: String): Staff = staffs.save(Staff(property.getId(), name, role))

        fun room(
            status: RoomOperationalStatus = RoomOperationalStatus.READY,
            availability: RoomAvailabilityStatus = RoomAvailabilityStatus.AVAILABLE,
        ): Room = rooms.save(
            Room(
                property.getId(), (++roomSeq).toString(), "STANDARD", 1, status, availability,
                availabilityReason = if (availability == RoomAvailabilityStatus.OUT_OF_SERVICE) "에어컨 수리" else null,
            ),
        )

        /** 기본: 1시간 전 체크인 예정, 23시간 뒤 체크아웃 예정. */
        fun reservation(
            room: Room,
            status: ReservationStatus = ReservationStatus.CONFIRMED,
            checkIn: Instant = Instant.now().minus(Duration.ofHours(1)),
            checkOut: Instant = Instant.now().plus(Duration.ofHours(23)),
        ): Reservation = reservations.save(
            Reservation(
                propertyId = property.getId(), roomId = room.getId(),
                scheduledCheckInAt = checkIn, scheduledCheckOutAt = checkOut, guestCount = 2, status = status,
                actualCheckInAt = if (status == ReservationStatus.CHECKED_IN || status == ReservationStatus.CHECKED_OUT) checkIn else null,
                actualCheckOutAt = if (status == ReservationStatus.CHECKED_OUT) checkOut else null,
            ),
        )

        /** CHECKED_IN 투숙 + 지정 room 상태 (stayover 시나리오용). */
        fun stay(roomStatus: RoomOperationalStatus = RoomOperationalStatus.OCCUPIED): Pair<Room, Reservation> {
            val room = room(roomStatus)
            return room to reservation(room, ReservationStatus.CHECKED_IN)
        }

        /**
         * 지정 상태의 STAYOVER_CLEANING task. [started]면 actualStartedAt을 기록한다.
         * [assigned]면 활성 assignment, [assignmentEndedOnResume]면 종료된 assignment를 남긴다.
         */
        fun stayoverTask(
            room: Room,
            reservation: Reservation,
            status: TaskStatus,
            statusBeforeBlocked: TaskStatus? = null,
            started: Boolean = false,
            assigned: Boolean = false,
            blocked: Boolean = false,
            assignmentEndedOnResume: Boolean = false,
        ): HousekeepingTask {
            val now = Instant.now()
            val task = tasks.save(
                HousekeepingTask(
                    propertyId = property.getId(), roomId = room.getId(), type = TaskType.STAYOVER_CLEANING,
                    origin = TaskOrigin.STAYOVER_REQUEST, scheduledAt = now, estimatedDurationMinutes = 25,
                    durationSource = DurationSource.USER_INPUT, createdBy = frontDesk.getId(), reservationId = reservation.getId(),
                    status = status, statusBeforeBlocked = statusBeforeBlocked,
                    actualStartedAt = if (started) now.minusSeconds(600) else null,
                    actualCompletedAt = if (status == TaskStatus.COMPLETED) now.minusSeconds(60) else null,
                ),
            )
            if (assigned) assignments.save(TaskAssignment(task.getId(), housekeeper.getId(), now.minusSeconds(900), manager.getId()))
            if (assignmentEndedOnResume) {
                assignments.save(
                    TaskAssignment(
                        task.getId(), housekeeper.getId(), now.minusSeconds(900), manager.getId(),
                        unassignedAt = now.minusSeconds(120), unassignedReason = UnassignedReason.ASSIGNEE_INVALID_ON_RESUME,
                    ),
                )
            }
            if (blocked) blockers.save(TaskBlocker(task.getId(), BlockerType.DND, now.minusSeconds(300)))
            return task
        }
    }

    fun newHotel(): Hotel = Hotel()
}
