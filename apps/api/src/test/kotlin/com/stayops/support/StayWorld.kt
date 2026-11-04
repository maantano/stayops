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
import com.stayops.staff.domain.StaffShift
import com.stayops.staff.infrastructure.StaffRepository
import com.stayops.staff.infrastructure.StaffShiftRepository
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
    private val shifts: StaffShiftRepository,
    private val inspectionRecords: com.stayops.housekeeping.infrastructure.InspectionRecordRepository,
) {
    inner class Hotel {
        val property: Property = properties.save(
            Property("Synthetic Hotel ${UUID.randomUUID()}", ZoneId.of("Asia/Seoul"), LocalTime.of(15, 0), LocalTime.of(11, 0), 30),
        )
        val manager: Staff = staff(StaffRole.MANAGER, "MGR-01")
        val frontDesk: Staff = staff(StaffRole.FRONT_DESK, "FD-01")
        val housekeeper: Staff = staff(StaffRole.HOUSEKEEPER, "HK-01").also { onShift(it) }
        val inspector: Staff = staff(StaffRole.INSPECTOR, "IN-01").also { onShift(it) }
        private var roomSeq = 100

        /** 기본: 2시간 전부터 8시간 뒤까지 근무 (테스트 가정). */
        fun onShift(
            staff: Staff,
            from: Instant = Instant.now().minus(Duration.ofHours(2)),
            to: Instant = Instant.now().plus(Duration.ofHours(8)),
        ): StaffShift = shifts.save(StaffShift(staff.getId(), from, to))

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
         * 임의 상태의 task. [started]면 actualStartedAt, [assignee]가 있으면 활성 assignment,
         * [assignmentEndedOnResume]면 종료된 assignment, [blocked]면 활성 DND blocker를 남긴다.
         */
        fun task(
            room: Room,
            type: TaskType,
            status: TaskStatus,
            reservation: Reservation? = null,
            source: HousekeepingTask? = null,
            statusBeforeBlocked: TaskStatus? = null,
            started: Boolean = false,
            assignee: Staff? = null,
            blocked: Boolean = false,
            assignmentEndedOnResume: Boolean = false,
            scheduledAt: Instant = Instant.now(),
        ): HousekeepingTask {
            val now = Instant.now()
            val origin = when (type) {
                TaskType.CHECKOUT_CLEANING -> TaskOrigin.CHECKOUT_COMMAND
                TaskType.STAYOVER_CLEANING -> TaskOrigin.STAYOVER_REQUEST
                TaskType.INSPECTION -> TaskOrigin.CLEANING_COMPLETED
                TaskType.RE_CLEANING -> if (source != null) TaskOrigin.INSPECTION_FAILED else TaskOrigin.MANAGER_REWORK
            }
            val task = tasks.save(
                HousekeepingTask(
                    propertyId = property.getId(), roomId = room.getId(), type = type, origin = origin,
                    originReason = if (origin == TaskOrigin.MANAGER_REWORK) "테스트 재정비" else null,
                    scheduledAt = scheduledAt, estimatedDurationMinutes = 25, durationSource = DurationSource.USER_INPUT,
                    createdBy = frontDesk.getId(), reservationId = reservation?.getId(), sourceTaskId = source?.getId(),
                    status = status, statusBeforeBlocked = statusBeforeBlocked,
                    actualStartedAt = if (started || status == TaskStatus.COMPLETED) now.minusSeconds(600) else null,
                    actualCompletedAt = if (status == TaskStatus.COMPLETED) now.minusSeconds(60) else null,
                    cancellationCode = if (status == TaskStatus.CANCELLED) com.stayops.housekeeping.domain.CancellationCode.MANUAL else null,
                    cancellationReason = if (status == TaskStatus.CANCELLED) "테스트 취소" else null,
                ),
            )
            if (assignee != null) assignments.save(TaskAssignment(task.getId(), assignee.getId(), now.minusSeconds(900), manager.getId()))
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

        /** 완료된 검사 task에 결과 기록을 남긴다. */
        fun inspectionRecord(inspection: HousekeepingTask, result: com.stayops.housekeeping.domain.InspectionResult) =
            inspectionRecords.save(
                com.stayops.housekeeping.domain.InspectionRecord(
                    inspection.getId(), result, "{}", inspector.getId(), Instant.now().minusSeconds(30),
                    failureReason = if (result == com.stayops.housekeeping.domain.InspectionResult.FAILED) "테스트 실패 사유" else null,
                ),
            )

        /** 지정 상태의 STAYOVER_CLEANING task (P3 테스트 호환). */
        fun stayoverTask(
            room: Room,
            reservation: Reservation,
            status: TaskStatus,
            statusBeforeBlocked: TaskStatus? = null,
            started: Boolean = false,
            assigned: Boolean = false,
            blocked: Boolean = false,
            assignmentEndedOnResume: Boolean = false,
        ): HousekeepingTask = task(
            room, TaskType.STAYOVER_CLEANING, status, reservation = reservation, statusBeforeBlocked = statusBeforeBlocked,
            started = started, assignee = if (assigned) housekeeper else null, blocked = blocked,
            assignmentEndedOnResume = assignmentEndedOnResume,
        )

        /** 체크아웃된 객실 + 체크아웃 청소 task (turnover 시나리오용). */
        fun turnover(
            roomStatus: RoomOperationalStatus = RoomOperationalStatus.VACANT_DIRTY,
            status: TaskStatus = TaskStatus.PENDING,
            statusBeforeBlocked: TaskStatus? = null,
            started: Boolean = false,
            assignee: Staff? = null,
            blocked: Boolean = false,
        ): Pair<Room, HousekeepingTask> {
            val room = room(roomStatus)
            val reservation = reservation(
                room, ReservationStatus.CHECKED_OUT,
                checkIn = Instant.now().minus(Duration.ofHours(26)), checkOut = Instant.now().minus(Duration.ofHours(2)),
            )
            return room to task(room, TaskType.CHECKOUT_CLEANING, status, reservation, null, statusBeforeBlocked, started, assignee, blocked)
        }
    }

    fun newHotel(): Hotel = Hotel()
}
