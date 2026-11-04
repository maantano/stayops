package com.stayops.schema

import com.stayops.TestcontainersConfiguration
import com.stayops.housekeeping.domain.BlockerResolutionType
import com.stayops.housekeeping.domain.BlockerType
import com.stayops.housekeeping.domain.CancellationCode
import com.stayops.housekeeping.domain.DurationSource
import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.InspectionRecord
import com.stayops.housekeeping.domain.InspectionResult
import com.stayops.housekeeping.domain.TaskAssignment
import com.stayops.housekeeping.domain.TaskBlocker
import com.stayops.housekeeping.domain.TaskOrigin
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.housekeeping.domain.UnassignedReason
import com.stayops.housekeeping.infrastructure.HousekeepingTaskRepository
import com.stayops.housekeeping.infrastructure.InspectionRecordRepository
import com.stayops.housekeeping.infrastructure.TaskAssignmentRepository
import com.stayops.housekeeping.infrastructure.TaskBlockerRepository
import com.stayops.operations.domain.ActorType
import com.stayops.operations.domain.OperationalEvent
import com.stayops.operations.infrastructure.OperationalEventRepository
import com.stayops.property.domain.Property
import com.stayops.reservation.domain.ReservationStatus
import com.stayops.reservation.infrastructure.ReservationRepository
import com.stayops.room.domain.Room
import com.stayops.room.domain.RoomAvailabilityStatus
import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.room.infrastructure.RoomRepository
import com.stayops.staff.domain.Staff
import com.stayops.staff.domain.StaffRole
import com.stayops.staff.domain.StaffShift
import com.stayops.staff.infrastructure.StaffShiftRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1 스키마의 DB 제약을 실제 PostgreSQL(Testcontainers)에서 검증한다 (v4.1 02 §10.2, 05 §2).
 * 각 테스트는 transaction 안에서 실행되고 rollback된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration::class)
class SchemaConstraintTests(
    @Autowired private val em: TestEntityManager,
    @Autowired private val rooms: RoomRepository,
    @Autowired private val reservations: ReservationRepository,
    @Autowired private val shifts: StaffShiftRepository,
    @Autowired private val tasks: HousekeepingTaskRepository,
    @Autowired private val assignments: TaskAssignmentRepository,
    @Autowired private val blockers: TaskBlockerRepository,
    @Autowired private val inspections: InspectionRecordRepository,
    @Autowired private val events: OperationalEventRepository,
) {
    private lateinit var f: SchemaFixtures
    private lateinit var property: Property
    private lateinit var room: Room

    @BeforeEach
    fun setUp() {
        f = SchemaFixtures(em)
        property = f.property()
        room = f.room(property)
    }

    private fun assertViolates(constraint: String, action: () -> Unit) {
        val ex = assertThrows<DataIntegrityViolationException> { action() }
        val message = generateSequence<Throwable>(ex) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue(message.contains(constraint), "expected violation of '$constraint' but was: $message")
    }

    // ---------------------------------------------------------------- room

    @Test
    fun `room number is unique within a property`() {
        assertViolates("room_number_unique_per_property") {
            rooms.saveAndFlush(Room(property.getId(), "101", "DELUXE", 1, RoomOperationalStatus.READY))
        }
    }

    @Test
    fun `same room number is allowed in another property`() {
        val other = f.property("Other Synthetic Hotel")
        rooms.saveAndFlush(Room(other.getId(), "101", "STANDARD", 1, RoomOperationalStatus.READY))
    }

    @Test
    fun `out of service room requires a reason`() {
        assertViolates("room_oos_requires_reason") {
            rooms.saveAndFlush(
                Room(property.getId(), "102", "STANDARD", 1, RoomOperationalStatus.READY, RoomAvailabilityStatus.OUT_OF_SERVICE),
            )
        }
    }

    // --------------------------------------------------------- reservation

    @Test
    fun `reservation check-in must be before check-out`() {
        assertViolates("reservation_interval_valid") {
            reservations.saveAndFlush(f.newReservation(room, fromHour = 10, toHour = 10))
        }
    }

    @Test
    fun `overlapping active reservations on the same room are rejected`() {
        f.reservation(room, fromHour = 0, toHour = 24)
        assertViolates("reservation_no_overlap") {
            reservations.saveAndFlush(f.newReservation(room, fromHour = 12, toHour = 36))
        }
    }

    @Test
    fun `back-to-back reservations do not overlap because intervals are half-open`() {
        f.reservation(room, fromHour = 0, toHour = 24)
        reservations.saveAndFlush(f.newReservation(room, fromHour = 24, toHour = 48))
    }

    @Test
    fun `cancelled reservation does not block a new overlapping reservation`() {
        f.reservation(room, fromHour = 0, toHour = 24, status = ReservationStatus.CANCELLED)
        reservations.saveAndFlush(f.newReservation(room, fromHour = 0, toHour = 24))
    }

    @Test
    fun `at most one checked-in reservation per room`() {
        f.reservation(room, fromHour = 0, toHour = 24, status = ReservationStatus.CHECKED_IN)
        assertViolates("reservation_one_checked_in_per_room") {
            reservations.saveAndFlush(f.newReservation(room, fromHour = 48, toHour = 72, status = ReservationStatus.CHECKED_IN))
        }
    }

    @Test
    fun `checked-in reservation requires actual check-in time`() {
        val r = f.newReservation(room, fromHour = 0, toHour = 24)
        val broken = com.stayops.reservation.domain.Reservation(
            propertyId = r.propertyId, roomId = r.roomId,
            scheduledCheckInAt = r.scheduledCheckInAt, scheduledCheckOutAt = r.scheduledCheckOutAt,
            guestCount = 1, status = ReservationStatus.CHECKED_IN, actualCheckInAt = null,
        )
        assertViolates("reservation_checked_in_has_time") { reservations.saveAndFlush(broken) }
    }

    @Test
    fun `reservation cannot reference a room of another property`() {
        val other = f.property("Other Synthetic Hotel")
        assertViolates("reservation_room_same_property") {
            reservations.saveAndFlush(f.newReservation(room, 0, 24, propertyId = other.getId()))
        }
    }

    // --------------------------------------------------------------- staff

    @Test
    fun `staff shifts of the same person cannot overlap`() {
        val staff = f.staff(property)
        shifts.saveAndFlush(StaffShift(staff.getId(), f.at(0), f.at(8)))
        assertViolates("staff_shift_no_overlap") {
            shifts.saveAndFlush(StaffShift(staff.getId(), f.at(7), f.at(15)))
        }
    }

    @Test
    fun `staff skills array and property timezone round-trip`() {
        val staff = em.persistAndFlush(
            Staff(property.getId(), "HK-02", StaffRole.HOUSEKEEPER, skills = listOf("SUITE", "DEEP_CLEAN")),
        )
        em.clear()
        val loadedStaff = assertNotNull(em.find(Staff::class.java, staff.getId()))
        assertEquals(listOf("SUITE", "DEEP_CLEAN"), loadedStaff.skills)
        assertEquals("Asia/Seoul", assertNotNull(em.find(Property::class.java, property.getId())).zoneId.id)
    }

    // ---------------------------------------------------------------- task

    @Test
    fun `only one active workflow task per room`() {
        f.task(room)
        assertViolates("task_one_active_workflow_per_room") { tasks.saveAndFlush(f.newTask(room)) }
    }

    @Test
    fun `terminal tasks do not count toward the active workflow limit`() {
        f.completedCleaning(room)
        tasks.saveAndFlush(f.newTask(room))
    }

    @Test
    fun `task cannot reference a room of another property`() {
        val other = f.property("Other Synthetic Hotel")
        assertViolates("task_room_same_property") {
            tasks.saveAndFlush(f.newTask(room, propertyId = other.getId()))
        }
    }

    @Test
    fun `blocked task must store status before blocked`() {
        assertViolates("task_blocked_iff_status_before_blocked") {
            tasks.saveAndFlush(f.newTask(room, status = TaskStatus.BLOCKED, statusBeforeBlocked = null))
        }
    }

    @Test
    fun `non-blocked task must not store status before blocked`() {
        assertViolates("task_blocked_iff_status_before_blocked") {
            tasks.saveAndFlush(f.newTask(room, status = TaskStatus.PENDING, statusBeforeBlocked = TaskStatus.ASSIGNED))
        }
    }

    @Test
    fun `blocked task with prior status is valid`() {
        tasks.saveAndFlush(
            f.newTask(room, status = TaskStatus.BLOCKED, statusBeforeBlocked = TaskStatus.IN_PROGRESS, actualStartedAt = f.at(1)),
        )
    }

    @Test
    fun `cancelled task requires a cancellation code`() {
        assertViolates("task_cancelled_iff_cancellation_code") {
            tasks.saveAndFlush(f.newTask(room, status = TaskStatus.CANCELLED))
        }
    }

    @Test
    fun `manual cancellation requires a reason`() {
        val task = HousekeepingTask(
            propertyId = property.getId(), roomId = room.getId(), type = TaskType.RE_CLEANING,
            origin = TaskOrigin.MANAGER_REWORK, originReason = "재청소", scheduledAt = f.at(1),
            estimatedDurationMinutes = 30, durationSource = DurationSource.USER_INPUT, createdBy = f.actor,
            status = TaskStatus.CANCELLED, cancellationCode = CancellationCode.MANUAL, cancellationReason = "  ",
        )
        assertViolates("task_manual_cancel_requires_reason") { tasks.saveAndFlush(task) }
    }

    @Test
    fun `manager rework task requires an origin reason`() {
        assertViolates("task_rework_requires_reason") {
            tasks.saveAndFlush(f.newTask(room, originReason = null))
        }
    }

    @Test
    fun `in-progress task requires actual start time`() {
        assertViolates("task_in_progress_has_start") {
            tasks.saveAndFlush(f.newTask(room, status = TaskStatus.IN_PROGRESS, actualStartedAt = null))
        }
    }

    @Test
    fun `inspection task must reference the cleaning task it verifies`() {
        assertViolates("task_type_origin_consistent") {
            tasks.saveAndFlush(f.newTask(room, type = TaskType.INSPECTION, origin = TaskOrigin.CLEANING_COMPLETED, sourceTaskId = null))
        }
    }

    @Test
    fun `checkout cleaning must come from the checkout command with a reservation`() {
        assertViolates("task_type_origin_consistent") {
            tasks.saveAndFlush(f.newTask(room, type = TaskType.CHECKOUT_CLEANING, origin = TaskOrigin.MANAGER_REWORK))
        }
    }

    @Test
    fun `inspection created from a completed cleaning is valid`() {
        val cleaning = f.completedCleaning(room)
        tasks.saveAndFlush(
            f.newTask(room, type = TaskType.INSPECTION, origin = TaskOrigin.CLEANING_COMPLETED, originReason = null, sourceTaskId = cleaning.getId()),
        )
    }

    @Test
    fun `only one checkout cleaning per reservation`() {
        val reservation = f.reservation(room, status = ReservationStatus.CHECKED_OUT)
        fun checkoutCleaning(status: TaskStatus) = f.newTask(
            room, type = TaskType.CHECKOUT_CLEANING, origin = TaskOrigin.CHECKOUT_COMMAND, originReason = null,
            reservationId = reservation.getId(), status = status,
            actualStartedAt = if (status == TaskStatus.COMPLETED) f.at(25) else null,
            actualCompletedAt = if (status == TaskStatus.COMPLETED) f.at(26) else null,
        )
        tasks.saveAndFlush(checkoutCleaning(TaskStatus.COMPLETED))
        assertViolates("task_one_checkout_cleaning_per_reservation") {
            tasks.saveAndFlush(checkoutCleaning(TaskStatus.PENDING))
        }
    }

    @Test
    fun `multiple sequential stayover cleanings per reservation are allowed`() {
        val reservation = f.reservation(room, fromHour = 0, toHour = 72, status = ReservationStatus.CHECKED_IN)
        fun stayover(status: TaskStatus) = f.newTask(
            room, type = TaskType.STAYOVER_CLEANING, origin = TaskOrigin.STAYOVER_REQUEST, originReason = null,
            reservationId = reservation.getId(), status = status,
            actualStartedAt = if (status == TaskStatus.COMPLETED) f.at(10) else null,
            actualCompletedAt = if (status == TaskStatus.COMPLETED) f.at(11) else null,
        )
        tasks.saveAndFlush(stayover(TaskStatus.COMPLETED))
        tasks.saveAndFlush(stayover(TaskStatus.PENDING))
    }

    // ---------------------------------------------------------- assignment

    @Test
    fun `only one active assignment per task`() {
        val task = f.task(room)
        val staff = f.staff(property)
        em.persistAndFlush(TaskAssignment(task.getId(), staff.getId(), f.at(1), f.actor))
        assertViolates("assignment_one_active_per_task") {
            assignments.saveAndFlush(TaskAssignment(task.getId(), staff.getId(), f.at(2), f.actor))
        }
    }

    @Test
    fun `ended assignment keeps history and allows a new active one`() {
        val task = f.task(room)
        val staff = f.staff(property)
        em.persistAndFlush(
            TaskAssignment(task.getId(), staff.getId(), f.at(1), f.actor, f.at(2), UnassignedReason.REASSIGNED),
        )
        assignments.saveAndFlush(TaskAssignment(task.getId(), staff.getId(), f.at(2), f.actor))
    }

    @Test
    fun `unassigned time and reason are set together`() {
        val task = f.task(room)
        val staff = f.staff(property)
        assertViolates("assignment_unassigned_pair") {
            assignments.saveAndFlush(TaskAssignment(task.getId(), staff.getId(), f.at(1), f.actor, unassignedAt = f.at(2)))
        }
    }

    // ------------------------------------------------------------- blocker

    @Test
    fun `resolved blocker requires resolution type and resolver`() {
        val task = f.task(room)
        assertViolates("blocker_resolution_consistent") {
            blockers.saveAndFlush(TaskBlocker(task.getId(), BlockerType.DND, f.at(1), resolvedAt = f.at(2)))
        }
    }

    @Test
    fun `blocker closed by task cancellation is valid`() {
        val task = f.task(room)
        blockers.saveAndFlush(
            TaskBlocker(
                task.getId(), BlockerType.LINEN_SHORTAGE, f.at(1),
                resolvedAt = f.at(2), resolvedBy = f.actor, resolutionType = BlockerResolutionType.TASK_CANCELLED,
            ),
        )
    }

    @Test
    fun `OTHER blocker requires a description`() {
        val task = f.task(room)
        assertViolates("blocker_other_requires_description") {
            blockers.saveAndFlush(TaskBlocker(task.getId(), BlockerType.OTHER, f.at(1)))
        }
    }

    // ---------------------------------------------------------- inspection

    private fun inspectionTask(): HousekeepingTask {
        val cleaning = f.completedCleaning(room)
        return em.persistAndFlush(
            f.newTask(
                room, type = TaskType.INSPECTION, origin = TaskOrigin.CLEANING_COMPLETED, originReason = null,
                sourceTaskId = cleaning.getId(), status = TaskStatus.IN_PROGRESS, actualStartedAt = f.at(3),
            ),
        )
    }

    @Test
    fun `failed inspection requires a failure reason`() {
        val task = inspectionTask()
        val inspector = f.staff(property, StaffRole.INSPECTOR, "IN-01")
        assertViolates("inspection_failed_requires_reason") {
            inspections.saveAndFlush(InspectionRecord(task.getId(), InspectionResult.FAILED, "{}", inspector.getId(), f.at(4)))
        }
    }

    @Test
    fun `one inspection result per inspection task and checklist json round-trips`() {
        val task = inspectionTask()
        val inspector = f.staff(property, StaffRole.INSPECTOR, "IN-01")
        val record = em.persistAndFlush(
            InspectionRecord(task.getId(), InspectionResult.PASSED, """{"bathroom": true, "bed": true}""", inspector.getId(), f.at(4)),
        )
        em.clear()
        val loaded = assertNotNull(em.find(InspectionRecord::class.java, record.getId()))
        assertTrue(loaded.checklistSnapshotJson.contains("\"bathroom\""))

        assertViolates("inspection_record_task_id_key") {
            inspections.saveAndFlush(InspectionRecord(task.getId(), InspectionResult.PASSED, "{}", inspector.getId(), f.at(5)))
        }
    }

    // --------------------------------------------------------------- event

    @Test
    fun `operational event payload is stored as jsonb`() {
        val event = events.saveAndFlush(
            OperationalEvent(
                propertyId = property.getId(), aggregateType = "Room", aggregateId = room.getId(),
                eventType = "ROOM_STATUS_CHANGED", aggregateVersion = 1, actorType = ActorType.SYSTEM, actorId = null,
                correlationId = UUID.randomUUID(), payloadSchemaVersion = 1,
                payloadJson = """{"from": "VACANT_DIRTY", "to": "CLEANING"}""", occurredAt = f.at(1),
            ),
        )
        em.clear()
        val jsonType = em.entityManager
            .createNativeQuery("select jsonb_typeof(payload) from operational_event where id = :id")
            .setParameter("id", event.getId())
            .singleResult
        assertEquals("object", jsonType)
    }

    @Test
    fun `staff actor event requires actor id`() {
        assertViolates("event_staff_actor_has_id") {
            events.saveAndFlush(
                OperationalEvent(
                    propertyId = property.getId(), aggregateType = "Room", aggregateId = room.getId(),
                    eventType = "ROOM_STATUS_CHANGED", aggregateVersion = 1, actorType = ActorType.STAFF, actorId = null,
                    correlationId = UUID.randomUUID(), payloadSchemaVersion = 1, payloadJson = "{}", occurredAt = f.at(1),
                ),
            )
        }
    }
}
