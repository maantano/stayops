package com.stayops.reservation

import com.stayops.TestcontainersConfiguration
import com.stayops.housekeeping.domain.BlockerResolutionType
import com.stayops.housekeeping.domain.CancellationCode
import com.stayops.housekeeping.domain.DurationSource
import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.TaskOrigin
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.housekeeping.domain.UnassignedReason
import com.stayops.housekeeping.infrastructure.HousekeepingTaskRepository
import com.stayops.housekeeping.infrastructure.TaskAssignmentRepository
import com.stayops.housekeeping.infrastructure.TaskBlockerRepository
import com.stayops.operations.infrastructure.OperationalEventRepository
import com.stayops.reservation.domain.Reservation
import com.stayops.reservation.domain.ReservationStatus
import com.stayops.reservation.infrastructure.ReservationRepository
import com.stayops.room.domain.Room
import com.stayops.room.domain.RoomAvailabilityStatus
import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.room.infrastructure.RoomRepository
import com.stayops.staff.domain.Staff
import com.stayops.support.ApiClient
import com.stayops.support.ApiClient.Companion.assertProblem
import com.stayops.support.StayWorld
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P3 예약 check-in / check-out (v4.1 02 §4, 05 §3 Reservation·Checkout × Stayover, §4 동시성).
 * 실제 HTTP + PostgreSQL. 각 테스트는 독립된 호텔을 만든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class StayCommandTests(
    @Autowired private val json: JsonMapper,
    @Autowired private val world: StayWorld,
    @Autowired private val reservations: ReservationRepository,
    @Autowired private val rooms: RoomRepository,
    @Autowired private val tasks: HousekeepingTaskRepository,
    @Autowired private val assignments: TaskAssignmentRepository,
    @Autowired private val blockers: TaskBlockerRepository,
    @Autowired private val events: OperationalEventRepository,
    @LocalServerPort private val port: Int,
) {
    private lateinit var api: ApiClient
    private lateinit var hotel: StayWorld.Hotel

    @BeforeEach
    fun setUp() {
        api = ApiClient(port, json)
        hotel = world.newHotel()
    }

    private fun checkIn(r: Reservation, by: Staff = hotel.frontDesk, key: String = UUID.randomUUID().toString()) =
        api.post("/api/v1/reservations/${r.getId()}/check-in", staffId = by.getId(), idempotencyKey = key)

    private fun checkOut(r: Reservation, by: Staff = hotel.frontDesk, key: String = UUID.randomUUID().toString()) =
        api.post("/api/v1/reservations/${r.getId()}/check-out", staffId = by.getId(), idempotencyKey = key)

    private fun reload(r: Reservation) = reservations.findById(r.getId()).orElseThrow()
    private fun reload(room: Room) = rooms.findById(room.getId()).orElseThrow()
    private fun reload(t: HousekeepingTask) = tasks.findById(t.getId()).orElseThrow()
    private fun eventsOf(id: UUID) = events.findByAggregateIdOrderByOccurredAtAscIdAsc(id)

    // =================================================================== check-in

    @Nested
    inner class CheckIn {

        @Test
        fun `confirmed reservation on a READY and AVAILABLE room checks in`() {
            val room = hotel.room(RoomOperationalStatus.READY)
            val reservation = hotel.reservation(room)

            val response = checkIn(reservation)

            assertEquals(200, response.status, "body=${response.body}")
            assertEquals("CHECKED_IN", response.text("reservationStatus"))
            assertEquals("OCCUPIED", response.text("roomOperationalStatus"))
            assertEquals(ReservationStatus.CHECKED_IN, reload(reservation).status)
            assertNotNull(reload(reservation).actualCheckInAt)
            assertEquals(RoomOperationalStatus.OCCUPIED, reload(room).operationalStatus)

            val correlationId = UUID.fromString(response.text("correlationId"))
            assertEquals(listOf("RESERVATION_CHECKED_IN"), eventsOf(reservation.getId()).map { it.eventType })
            assertEquals(listOf("ROOM_STATUS_CHANGED"), eventsOf(room.getId()).map { it.eventType })
            assertTrue((eventsOf(reservation.getId()) + eventsOf(room.getId())).all { it.correlationId == correlationId })
            assertEquals(reload(reservation).version, response.body.get("reservationVersion").asLong())
        }

        @Test
        fun `room that is not READY is rejected without changes`() {
            val room = hotel.room(RoomOperationalStatus.VACANT_DIRTY)
            val reservation = hotel.reservation(room)

            val response = checkIn(reservation)

            assertProblem(response, 409, "INVALID_STATE")
            assertEquals("VACANT_DIRTY", response.text("roomOperationalStatus"))
            assertEquals(ReservationStatus.CONFIRMED, reload(reservation).status)
            assertTrue(eventsOf(reservation.getId()).isEmpty())
        }

        @Test
        fun `READY but OUT_OF_SERVICE room is rejected as not available`() {
            val room = hotel.room(RoomOperationalStatus.READY, RoomAvailabilityStatus.OUT_OF_SERVICE)
            val reservation = hotel.reservation(room)

            val response = checkIn(reservation)

            assertProblem(response, 409, "ROOM_NOT_AVAILABLE")
            assertEquals("OUT_OF_SERVICE", response.text("availabilityStatus"))
            assertEquals(RoomOperationalStatus.READY, reload(room).operationalStatus)
            assertEquals(ReservationStatus.CONFIRMED, reload(reservation).status)
        }

        @Test
        fun `cancelled reservation cannot check in`() {
            val room = hotel.room()
            val reservation = hotel.reservation(room, ReservationStatus.CANCELLED)
            val response = checkIn(reservation)
            assertProblem(response, 409, "INVALID_STATE")
            assertEquals("CANCELLED", response.text("currentStatus"))
        }

        @Test
        fun `reservation whose stay period already ended cannot check in`() {
            val room = hotel.room()
            val reservation = hotel.reservation(
                room, checkIn = Instant.now().minus(Duration.ofHours(48)), checkOut = Instant.now().minus(Duration.ofHours(24)),
            )
            assertProblem(checkIn(reservation), 409, "INVALID_STATE")
            assertEquals(RoomOperationalStatus.READY, reload(room).operationalStatus)
        }

        @Test
        fun `housekeeper cannot check in and other property's reservation is not found`() {
            val room = hotel.room()
            val reservation = hotel.reservation(room)
            assertProblem(checkIn(reservation, by = hotel.housekeeper), 403, "FORBIDDEN")

            val other = world.newHotel()
            assertProblem(checkIn(reservation, by = other.frontDesk), 404, "NOT_FOUND")
            assertEquals(ReservationStatus.CONFIRMED, reload(reservation).status)
        }

        @Test
        fun `retry with the same key replays and a new key after success is rejected`() {
            val room = hotel.room()
            val reservation = hotel.reservation(room)

            val first = checkIn(reservation, key = "ci-1")
            val replay = checkIn(reservation, key = "ci-1")
            val again = checkIn(reservation, key = "ci-2")

            assertEquals(200, first.status)
            assertEquals(first.body, replay.body)
            assertEquals("true", replay.header("Idempotency-Replayed"))
            assertProblem(again, 409, "INVALID_STATE")
            assertEquals(1, eventsOf(reservation.getId()).size)
        }

        @Test
        fun `concurrent check-ins of the same reservation succeed exactly once`() {
            val room = hotel.room()
            val reservation = hotel.reservation(room)

            val results = runConcurrently(2) { checkIn(reservation) }

            assertEquals(listOf(200, 409), results.map { it.status }.sorted())
            assertEquals(1, eventsOf(reservation.getId()).size)
        }
    }

    // ================================================================== check-out

    @Nested
    inner class CheckOut {

        @Test
        fun `checked-in stay checks out, room becomes VACANT_DIRTY and one checkout cleaning is created`() {
            val (room, reservation) = hotel.stay()

            val response = checkOut(reservation)

            assertEquals(200, response.status, "body=${response.body}")
            assertEquals(ReservationStatus.CHECKED_OUT, reload(reservation).status)
            assertNotNull(reload(reservation).actualCheckOutAt)
            assertEquals(RoomOperationalStatus.VACANT_DIRTY, reload(room).operationalStatus)

            val cleaning = assertNotNull(tasks.findByReservationIdAndType(reservation.getId(), TaskType.CHECKOUT_CLEANING))
            assertEquals(cleaning.getId().toString(), response.text("checkoutCleaningTaskId"))
            assertEquals(TaskStatus.PENDING, cleaning.status)
            assertEquals(TaskOrigin.CHECKOUT_COMMAND, cleaning.origin)
            assertEquals(DurationSource.CONFIG_DEFAULT, cleaning.durationSource)
            assertEquals(45, cleaning.estimatedDurationMinutes)
            assertNull(response.text("autoCancelledStayoverTaskId"))

            val correlationId = UUID.fromString(response.text("correlationId"))
            val all = eventsOf(reservation.getId()) + eventsOf(room.getId()) + eventsOf(cleaning.getId())
            assertEquals(setOf("RESERVATION_CHECKED_OUT", "ROOM_STATUS_CHANGED", "HOUSEKEEPING_TASK_CREATED"), all.map { it.eventType }.toSet())
            assertTrue(all.all { it.correlationId == correlationId })
        }

        @Test
        fun `reservation that is not checked in cannot check out`() {
            val room = hotel.room()
            val reservation = hotel.reservation(room)
            val response = checkOut(reservation)
            assertProblem(response, 409, "INVALID_STATE")
            assertEquals("CONFIRMED", response.text("currentStatus"))
        }

        @Test
        fun `housekeeper cannot check out`() {
            val (_, reservation) = hotel.stay()
            assertProblem(checkOut(reservation, by = hotel.housekeeper), 403, "FORBIDDEN")
            assertEquals(ReservationStatus.CHECKED_IN, reload(reservation).status)
        }

        @Test
        fun `retry with the same key replays and creates no second cleaning task`() {
            val (room, reservation) = hotel.stay()

            val first = checkOut(reservation, key = "co-1")
            val replay = checkOut(reservation, key = "co-1")
            val again = checkOut(reservation, key = "co-2")

            assertEquals(200, first.status)
            assertEquals(first.body, replay.body)
            assertProblem(again, 409, "INVALID_STATE")
            assertEquals(1, tasks.findByRoomIdOrderByCreatedAtAsc(room.getId()).size)
        }

        @Test
        fun `concurrent check-outs succeed exactly once`() {
            val (room, reservation) = hotel.stay()
            val results = runConcurrently(2) { checkOut(reservation) }
            assertEquals(listOf(200, 409), results.map { it.status }.sorted())
            assertEquals(1, tasks.findByRoomIdOrderByCreatedAtAsc(room.getId()).size)
        }

        @Test
        fun `failure while creating the checkout cleaning rolls back every change`() {
            // 장애 주입: 이 예약의 CHECKOUT_CLEANING이 이미 있으면 새 task 저장이 unique 제약으로 실패한다.
            val (room, reservation) = hotel.stay()
            val stayover = hotel.stayoverTask(room, reservation, TaskStatus.PENDING, blocked = false)
            tasks.save(
                HousekeepingTask(
                    propertyId = hotel.property.getId(), roomId = room.getId(), type = TaskType.CHECKOUT_CLEANING,
                    origin = TaskOrigin.CHECKOUT_COMMAND, scheduledAt = Instant.now(), estimatedDurationMinutes = 45,
                    durationSource = DurationSource.CONFIG_DEFAULT, createdBy = hotel.manager.getId(), reservationId = reservation.getId(),
                    status = TaskStatus.CANCELLED, cancellationCode = CancellationCode.MANUAL, cancellationReason = "fault injection",
                ),
            )

            val response = checkOut(reservation)

            assertProblem(response, 409, "INVALID_STATE")
            assertEquals(ReservationStatus.CHECKED_IN, reload(reservation).status)
            assertEquals(RoomOperationalStatus.OCCUPIED, reload(room).operationalStatus)
            assertEquals(TaskStatus.PENDING, reload(stayover).status, "auto-cancel of the stayover must be rolled back")
            assertTrue(eventsOf(reservation.getId()).isEmpty())
            assertTrue(eventsOf(stayover.getId()).isEmpty())
        }
    }

    // ============================================== check-out × stayover (05 §3)

    @Nested
    inner class CheckOutWithStayover {

        private fun assertAutoCancelled(stayover: HousekeepingTask, reservation: Reservation, response: ApiClient.Response) {
            assertEquals(200, response.status, "body=${response.body}")
            assertEquals(stayover.getId().toString(), response.text("autoCancelledStayoverTaskId"))
            val cancelled = reload(stayover)
            assertEquals(TaskStatus.CANCELLED, cancelled.status)
            assertEquals(CancellationCode.CHECKOUT, cancelled.cancellationCode)
            assertNull(cancelled.statusBeforeBlocked)
            assertNull(assignments.findByTaskIdAndUnassignedAtIsNull(stayover.getId()), "assignment must be ended")
            assertTrue(blockers.findByTaskIdAndResolvedAtIsNullOrderByBlockedAtAsc(stayover.getId()).isEmpty(), "blockers must be closed")
            assertEquals(ReservationStatus.CHECKED_OUT, reload(reservation).status)
            val correlationId = UUID.fromString(response.text("correlationId"))
            assertEquals(listOf("TASK_CANCELLED"), eventsOf(stayover.getId()).map { it.eventType })
            assertEquals(correlationId, eventsOf(stayover.getId()).single().correlationId)
        }

        private fun assertRejected(
            stayover: HousekeepingTask,
            reservation: Reservation,
            room: Room,
            response: ApiClient.Response,
            expectedRoomStatus: RoomOperationalStatus,
        ) {
            assertProblem(response, 409, "STAYOVER_IN_PROGRESS")
            val blocking = response.body.get("blockingTask")
            assertEquals(stayover.getId().toString(), blocking.get("taskId").asString())
            assertNotNull(blocking.get("actualStartedAt").asString())
            assertEquals(ReservationStatus.CHECKED_IN, reload(reservation).status)
            assertEquals(expectedRoomStatus, reload(room).operationalStatus)
            assertEquals(stayover.status, reload(stayover).status)
            assertTrue(eventsOf(reservation.getId()).isEmpty())
            assertTrue(tasks.findByReservationIdAndType(reservation.getId(), TaskType.CHECKOUT_CLEANING) == null)
        }

        @Test
        fun `PENDING stayover is auto-cancelled`() {
            val (room, reservation) = hotel.stay()
            val stayover = hotel.stayoverTask(room, reservation, TaskStatus.PENDING)
            assertAutoCancelled(stayover, reservation, checkOut(reservation))
        }

        @Test
        fun `ASSIGNED stayover is auto-cancelled and its assignment ended`() {
            val (room, reservation) = hotel.stay()
            val stayover = hotel.stayoverTask(room, reservation, TaskStatus.ASSIGNED, assigned = true)
            assertAutoCancelled(stayover, reservation, checkOut(reservation))
            val ended = assignments.findByTaskIdOrderByAssignedAtAsc(stayover.getId()).single()
            assertEquals(UnassignedReason.TASK_CANCELLED, ended.unassignedReason)
        }

        @Test
        fun `BLOCKED stayover that was PENDING is auto-cancelled and its blocker closed`() {
            val (room, reservation) = hotel.stay()
            val stayover = hotel.stayoverTask(room, reservation, TaskStatus.BLOCKED, TaskStatus.PENDING, blocked = true)
            assertAutoCancelled(stayover, reservation, checkOut(reservation))
            val closed = blockers.findByTaskIdOrderByBlockedAtAsc(stayover.getId()).single()
            assertEquals(BlockerResolutionType.TASK_CANCELLED, closed.resolutionType)
            assertEquals(hotel.frontDesk.getId(), closed.resolvedBy)
        }

        @Test
        fun `BLOCKED stayover that was ASSIGNED is auto-cancelled with assignment and blocker closed`() {
            val (room, reservation) = hotel.stay()
            val stayover = hotel.stayoverTask(room, reservation, TaskStatus.BLOCKED, TaskStatus.ASSIGNED, assigned = true, blocked = true)
            assertAutoCancelled(stayover, reservation, checkOut(reservation))
        }

        @Test
        fun `IN_PROGRESS stayover rejects check-out`() {
            val (room, reservation) = hotel.stay(RoomOperationalStatus.STAYOVER_CLEANING)
            val stayover = hotel.stayoverTask(room, reservation, TaskStatus.IN_PROGRESS, started = true, assigned = true)
            val response = checkOut(reservation)
            assertRejected(stayover, reservation, room, response, RoomOperationalStatus.STAYOVER_CLEANING)
            assertEquals("HK-01", response.body.get("blockingTask").get("assignee").get("displayName").asString())
        }

        @Test
        fun `BLOCKED stayover that was IN_PROGRESS rejects check-out and lists blockers`() {
            val (room, reservation) = hotel.stay(RoomOperationalStatus.STAYOVER_CLEANING)
            val stayover = hotel.stayoverTask(
                room, reservation, TaskStatus.BLOCKED, TaskStatus.IN_PROGRESS, started = true, assigned = true, blocked = true,
            )
            val response = checkOut(reservation)
            assertRejected(stayover, reservation, room, response, RoomOperationalStatus.STAYOVER_CLEANING)
            assertEquals("IN_PROGRESS", response.body.get("blockingTask").get("statusBeforeBlocked").asString())
            assertEquals("DND", response.body.get("blockingTask").get("activeBlockers").get(0).get("type").asString())
        }

        @Test
        fun `PENDING stayover that was already started (assignee invalid on resume) rejects check-out`() {
            // v4.1 N1: status만 보면 자동 취소 대상이지만 actualStartedAt 기준으로는 시작된 작업이다.
            val (room, reservation) = hotel.stay(RoomOperationalStatus.STAYOVER_CLEANING)
            val stayover = hotel.stayoverTask(room, reservation, TaskStatus.PENDING, started = true, assignmentEndedOnResume = true)
            assertRejected(stayover, reservation, room, checkOut(reservation), RoomOperationalStatus.STAYOVER_CLEANING)
        }

        @Test
        fun `completed stayover does not affect check-out and stays in history`() {
            val (room, reservation) = hotel.stay()
            val stayover = hotel.stayoverTask(room, reservation, TaskStatus.COMPLETED, started = true)
            val response = checkOut(reservation)
            assertEquals(200, response.status, "body=${response.body}")
            assertNull(response.text("autoCancelledStayoverTaskId"))
            assertEquals(TaskStatus.COMPLETED, reload(stayover).status)
        }

        @Test
        fun `resolution options depend on the requester's role`() {
            val (room, reservation) = hotel.stay(RoomOperationalStatus.STAYOVER_CLEANING)
            hotel.stayoverTask(room, reservation, TaskStatus.IN_PROGRESS, started = true, assigned = true)

            val byFrontDesk = checkOut(reservation, by = hotel.frontDesk)
            val byManager = checkOut(reservation, by = hotel.manager)

            assertEquals(listOf("RESOLVE_BLOCKERS_AND_COMPLETE"), strings(byFrontDesk.body.get("resolutionOptions")))
            assertEquals(
                listOf("RESOLVE_BLOCKERS_AND_COMPLETE", "CANCEL_TASK_AS_MANAGER"),
                strings(byManager.body.get("resolutionOptions")),
            )
        }
    }

    private fun strings(node: tools.jackson.databind.JsonNode): List<String> = (0 until node.size()).map { node.get(it).asString() }

    private fun <T> runConcurrently(n: Int, action: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(n)
        val ready = CountDownLatch(n)
        val go = CountDownLatch(1)
        try {
            val futures = (0 until n).map { i -> pool.submit(Callable { ready.countDown(); go.await(); action(i) }) }
            ready.await()
            go.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
