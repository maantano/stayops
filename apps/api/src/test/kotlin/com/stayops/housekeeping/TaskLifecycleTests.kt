package com.stayops.housekeeping

import com.stayops.TestcontainersConfiguration
import com.stayops.housekeeping.domain.BlockerResolutionType
import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.TaskOrigin
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.housekeeping.domain.UnassignedReason
import com.stayops.housekeeping.infrastructure.HousekeepingTaskRepository
import com.stayops.housekeeping.infrastructure.TaskAssignmentRepository
import com.stayops.housekeeping.infrastructure.TaskBlockerRepository
import com.stayops.operations.infrastructure.OperationalEventRepository
import com.stayops.reservation.domain.ReservationStatus
import com.stayops.reservation.infrastructure.ReservationRepository
import com.stayops.room.domain.Room
import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.room.infrastructure.RoomRepository
import com.stayops.staff.domain.Staff
import com.stayops.staff.domain.StaffRole
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
 * P4 작업 lifecycle (docs/v4.1/02 §3.2·§3.3·§4, 05 §3 Task lifecycle·Task start·Cancel matrix, §4 동시성).
 * 실제 HTTP + PostgreSQL. 각 테스트는 독립된 호텔을 만든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class TaskLifecycleTests(
    @Autowired private val json: JsonMapper,
    @Autowired private val world: StayWorld,
    @Autowired private val tasks: HousekeepingTaskRepository,
    @Autowired private val rooms: RoomRepository,
    @Autowired private val reservations: ReservationRepository,
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

    // --------------------------------------------------------------- helpers

    private fun path(task: HousekeepingTask, suffix: String) = "/api/v1/tasks/${task.getId()}$suffix"
    private fun key() = UUID.randomUUID().toString()

    private fun assign(task: HousekeepingTask, staff: Staff, by: Staff = hotel.manager) =
        api.post(path(task, "/assignments"), mapOf("staffId" to staff.getId()), by.getId(), key())
    private fun start(task: HousekeepingTask, by: Staff = hotel.housekeeper, key: String = key()) =
        api.post(path(task, "/start"), staffId = by.getId(), idempotencyKey = key)
    private fun block(task: HousekeepingTask, by: Staff = hotel.manager, type: String = "LINEN_SHORTAGE", extra: Map<String, Any?> = emptyMap()) =
        api.post(path(task, "/blockers"), mapOf("type" to type) + extra, by.getId(), key())
    private fun resolve(task: HousekeepingTask, blockerId: String, by: Staff = hotel.manager) =
        api.post(path(task, "/blockers/$blockerId/resolve"), mapOf("note" to "해결"), by.getId(), key())
    private fun complete(task: HousekeepingTask, by: Staff = hotel.housekeeper) =
        api.post(path(task, "/complete"), staffId = by.getId(), idempotencyKey = key())
    private fun cancel(task: HousekeepingTask, by: Staff = hotel.manager, reason: String? = "운영 계획 변경") =
        api.post(path(task, "/cancel"), mapOf("reason" to reason), by.getId(), key())

    private fun reload(t: HousekeepingTask) = tasks.findById(t.getId()).orElseThrow()
    private fun reload(r: Room) = rooms.findById(r.getId()).orElseThrow()
    private fun activeAssignment(t: HousekeepingTask) = assignments.findByTaskIdAndUnassignedAtIsNull(t.getId())
    private fun activeBlockers(t: HousekeepingTask) = blockers.findByTaskIdAndResolvedAtIsNullOrderByBlockedAtAsc(t.getId())
    private fun eventTypes(id: UUID) = events.findByAggregateIdOrderByOccurredAtAscIdAsc(id).map { it.eventType }

    private fun offShiftHousekeeper(): Staff = hotel.staff(StaffRole.HOUSEKEEPER, "HK-OFF").also {
        hotel.onShift(it, Instant.now().minus(Duration.ofHours(10)), Instant.now().minus(Duration.ofHours(2)))
    }

    /** 완료된 청소 + 그 청소를 확인할 ASSIGNED 검사 task. */
    private fun inspectionReady(): Triple<Room, HousekeepingTask, HousekeepingTask> {
        val (room, cleaning) = hotel.turnover(status = TaskStatus.COMPLETED)
        val inspection = hotel.task(room, TaskType.INSPECTION, TaskStatus.ASSIGNED, source = cleaning, assignee = hotel.inspector)
        return Triple(room, cleaning, inspection)
    }

    // ================================================================= assign

    @Nested
    inner class Assign {
        @Test
        fun `manager assigns a pending task to an on-shift housekeeper`() {
            val (_, task) = hotel.turnover()
            val response = assign(task, hotel.housekeeper)

            assertEquals(200, response.status, "body=${response.body}")
            assertEquals("ASSIGNED", response.text("taskStatus"))
            assertEquals(TaskStatus.ASSIGNED, reload(task).status)
            assertEquals(hotel.housekeeper.getId(), activeAssignment(task)?.staffId)
            assertEquals(listOf("TASK_ASSIGNED"), eventTypes(task.getId()))
        }

        @Test
        fun `reassignment ends the previous assignment and keeps history`() {
            val (_, task) = hotel.turnover(status = TaskStatus.ASSIGNED, assignee = hotel.housekeeper)
            val other = hotel.staff(StaffRole.HOUSEKEEPER, "HK-02").also { hotel.onShift(it) }

            assertEquals(200, assign(task, other).status)

            val history = assignments.findByTaskIdOrderByAssignedAtAsc(task.getId())
            assertEquals(2, history.size)
            assertEquals(UnassignedReason.REASSIGNED, history[0].unassignedReason)
            assertEquals(other.getId(), activeAssignment(task)?.staffId)
        }

        @Test
        fun `assigning the same staff again changes nothing`() {
            val (_, task) = hotel.turnover(status = TaskStatus.ASSIGNED, assignee = hotel.housekeeper)
            assertEquals(200, assign(task, hotel.housekeeper).status)
            assertEquals(1, assignments.findByTaskIdOrderByAssignedAtAsc(task.getId()).size)
            assertTrue(eventTypes(task.getId()).isEmpty())
        }

        @Test
        fun `assignee must have the role required by the task type`() {
            val (_, task) = hotel.turnover()
            val response = assign(task, hotel.inspector)
            assertProblem(response, 409, "ASSIGNMENT_INVALID")
            assertTrue(response.text("reason")!!.startsWith("ROLE_MISMATCH"))
            assertProblem(assign(task, hotel.manager), 409, "ASSIGNMENT_INVALID")
        }

        @Test
        fun `assignee must be on shift at the scheduled time`() {
            val (_, task) = hotel.turnover()
            assertProblem(assign(task, offShiftHousekeeper()), 409, "SHIFT_CONFLICT")
            assertEquals(TaskStatus.PENDING, reload(task).status)
        }

        @Test
        fun `expected end after shift end is a warning, not an error`() {
            val (_, task) = hotel.turnover()
            val late = hotel.staff(StaffRole.HOUSEKEEPER, "HK-LATE").also {
                hotel.onShift(it, Instant.now().minus(Duration.ofHours(7)), Instant.now().plus(Duration.ofMinutes(10)))
            }
            val response = assign(task, late)
            assertEquals(200, response.status)
            assertEquals("EXPECTED_END_AFTER_SHIFT_END", response.body.get("warnings").get(0).asString())
        }

        @Test
        fun `in-progress task cannot be reassigned and only managers assign`() {
            val (_, task) = hotel.turnover(RoomOperationalStatus.CLEANING, TaskStatus.IN_PROGRESS, started = true, assignee = hotel.housekeeper)
            assertProblem(assign(task, hotel.housekeeper), 409, "INVALID_STATE")
            val (_, pending) = hotel.turnover()
            assertProblem(assign(pending, hotel.housekeeper, by = hotel.housekeeper), 403, "FORBIDDEN")
        }

        @Test
        fun `manager unassigns an assigned task back to pending`() {
            val (_, task) = hotel.turnover(status = TaskStatus.ASSIGNED, assignee = hotel.housekeeper)
            val assignmentId = activeAssignment(task)!!.getId()

            val response = api.delete(path(task, "/assignments/$assignmentId"), hotel.manager.getId())

            assertEquals(200, response.status, "body=${response.body}")
            assertEquals(TaskStatus.PENDING, reload(task).status)
            assertEquals(UnassignedReason.MANUAL_UNASSIGN, assignments.findById(assignmentId).orElseThrow().unassignedReason)
        }
    }

    // ============================================== start (02 §4 판정 표)

    @Nested
    inner class Start {
        @Test
        fun `row 9 - first start of a checkout cleaning moves the room to CLEANING`() {
            val (room, task) = hotel.turnover(status = TaskStatus.ASSIGNED, assignee = hotel.housekeeper)
            val response = start(task)

            assertEquals(200, response.status, "body=${response.body}")
            assertEquals(false, response.body.get("alreadyStarted").asBoolean())
            assertEquals(TaskStatus.IN_PROGRESS, reload(task).status)
            assertNotNull(reload(task).actualStartedAt)
            assertEquals(RoomOperationalStatus.CLEANING, reload(room).operationalStatus)
            assertEquals(listOf("TASK_STARTED"), eventTypes(task.getId()))
            assertEquals(listOf("ROOM_STATUS_CHANGED"), eventTypes(room.getId()))
        }

        @Test
        fun `row 9 - first start of a stayover moves an occupied room to STAYOVER_CLEANING`() {
            val (room, reservation) = hotel.stay()
            val task = hotel.stayoverTask(room, reservation, TaskStatus.ASSIGNED, assigned = true)
            assertEquals(200, start(task).status)
            assertEquals(RoomOperationalStatus.STAYOVER_CLEANING, reload(room).operationalStatus)
            assertEquals(ReservationStatus.CHECKED_IN, reservations.findById(reservation.getId()).orElseThrow().status)
        }

        @Test
        fun `row 9 - first start of an inspection moves the room to INSPECTING`() {
            val (room, _, inspection) = inspectionReady()
            assertEquals(200, start(inspection, by = hotel.inspector).status)
            assertEquals(RoomOperationalStatus.INSPECTING, reload(room).operationalStatus)
        }

        @Test
        fun `row 3 - start retry on an in-progress task is a no-op success`() {
            val (_, task) = hotel.turnover(status = TaskStatus.ASSIGNED, assignee = hotel.housekeeper)
            start(task)
            val afterFirst = reload(task)

            val byAssignee = start(task)
            val byManager = start(task, by = hotel.manager)

            assertEquals(200, byAssignee.status)
            assertEquals(true, byAssignee.body.get("alreadyStarted").asBoolean())
            assertEquals(200, byManager.status)
            assertEquals(afterFirst.version, reload(task).version)
            assertEquals(afterFirst.actualStartedAt, reload(task).actualStartedAt)
            assertEquals(listOf("TASK_STARTED"), eventTypes(task.getId()))
        }

        @Test
        fun `row 4 - another staff cannot touch an in-progress task`() {
            val (_, task) = hotel.turnover(RoomOperationalStatus.CLEANING, TaskStatus.IN_PROGRESS, started = true, assignee = hotel.housekeeper)
            val other = hotel.staff(StaffRole.HOUSEKEEPER, "HK-02").also { hotel.onShift(it) }
            assertProblem(start(task, by = other), 403, "FORBIDDEN")
        }

        @Test
        fun `row 2 - late start on a cancelled task is rejected with its current status`() {
            val (room, task) = hotel.turnover(status = TaskStatus.CANCELLED)
            val response = start(task)
            assertProblem(response, 409, "INVALID_STATE")
            assertEquals("CANCELLED", response.text("currentStatus"))
            assertEquals(RoomOperationalStatus.VACANT_DIRTY, reload(room).operationalStatus)
        }

        @Test
        fun `row 5 - blocked task lists its active blockers`() {
            val (_, task) = hotel.turnover(status = TaskStatus.BLOCKED, statusBeforeBlocked = TaskStatus.ASSIGNED, assignee = hotel.housekeeper, blocked = true)
            val response = start(task)
            assertProblem(response, 409, "ACTIVE_BLOCKER_EXISTS")
            assertEquals("DND", response.body.get("activeBlockers").get(0).get("type").asString())
        }

        @Test
        fun `row 6 - pending task without assignee cannot start`() {
            val (_, task) = hotel.turnover()
            assertProblem(start(task), 409, "ASSIGNMENT_INVALID")
        }

        @Test
        fun `row 7 - only the on-shift assignee can start (no manager proxy)`() {
            val (_, task) = hotel.turnover(status = TaskStatus.ASSIGNED, assignee = hotel.housekeeper)
            assertProblem(start(task, by = hotel.manager), 403, "FORBIDDEN")

            val off = offShiftHousekeeper()
            val (_, offTask) = hotel.turnover(status = TaskStatus.ASSIGNED, assignee = off)
            assertProblem(start(offTask, by = off), 409, "SHIFT_CONFLICT")
        }

        @Test
        fun `row 10 - restart keeps the first start time and does not change the room`() {
            val (room, task) = hotel.turnover(RoomOperationalStatus.CLEANING, TaskStatus.ASSIGNED, started = true, assignee = hotel.housekeeper)
            val firstStartedAt = reload(task).actualStartedAt

            val response = start(task)

            assertEquals(200, response.status, "body=${response.body}")
            assertEquals(TaskStatus.IN_PROGRESS, reload(task).status)
            assertEquals(firstStartedAt, reload(task).actualStartedAt)
            assertEquals(RoomOperationalStatus.CLEANING, reload(room).operationalStatus)
            assertEquals(listOf("TASK_RESUMED"), eventTypes(task.getId()))
            assertTrue(eventTypes(room.getId()).isEmpty())
        }

        @Test
        fun `row 11 - room state contradicting the task is reported, not repaired`() {
            val (room, firstStart) = hotel.turnover(RoomOperationalStatus.CLEANING, TaskStatus.ASSIGNED, assignee = hotel.housekeeper)
            assertProblem(start(firstStart), 409, "STATE_INCONSISTENT")
            assertEquals(RoomOperationalStatus.CLEANING, reload(room).operationalStatus)
            assertEquals(TaskStatus.ASSIGNED, reload(firstStart).status)

            val (room2, restart) = hotel.turnover(RoomOperationalStatus.VACANT_DIRTY, TaskStatus.ASSIGNED, started = true, assignee = hotel.housekeeper)
            assertProblem(start(restart), 409, "STATE_INCONSISTENT")
            assertEquals(RoomOperationalStatus.VACANT_DIRTY, reload(room2).operationalStatus)
        }

        @Test
        fun `row 1 - same idempotency key replays the first response`() {
            val (_, task) = hotel.turnover(status = TaskStatus.ASSIGNED, assignee = hotel.housekeeper)
            val first = start(task, key = "start-1")
            val replay = start(task, key = "start-1")
            assertEquals(first.body, replay.body)
            assertEquals("true", replay.header("Idempotency-Replayed"))
        }
    }

    // ================================================================ blockers

    @Nested
    inner class Blockers {
        @Test
        fun `blocker on a pending task stores PENDING as prior status (manager only without assignee)`() {
            val (_, task) = hotel.turnover()
            assertProblem(block(task, by = hotel.housekeeper), 403, "FORBIDDEN")

            val response = block(task)
            assertEquals(201, response.status, "body=${response.body}")
            assertEquals(TaskStatus.BLOCKED, reload(task).status)
            assertEquals(TaskStatus.PENDING, reload(task).statusBeforeBlocked)
        }

        @Test
        fun `blocking an in-progress task keeps the room CLEANING and a second blocker keeps the prior status`() {
            val (room, task) = hotel.turnover(RoomOperationalStatus.CLEANING, TaskStatus.IN_PROGRESS, started = true, assignee = hotel.housekeeper)
            assertEquals(201, block(task, by = hotel.housekeeper).status)
            assertEquals(201, block(task, by = hotel.housekeeper, type = "SUPPLY_SHORTAGE").status)

            assertEquals(TaskStatus.BLOCKED, reload(task).status)
            assertEquals(TaskStatus.IN_PROGRESS, reload(task).statusBeforeBlocked)
            assertEquals(RoomOperationalStatus.CLEANING, reload(room).operationalStatus)
            assertEquals(2, activeBlockers(task).size)
        }

        @Test
        fun `task stays blocked until the last blocker is resolved, then returns to its prior status`() {
            val (_, task) = hotel.turnover(RoomOperationalStatus.CLEANING, TaskStatus.IN_PROGRESS, started = true, assignee = hotel.housekeeper)
            val b1 = block(task).text("blockerId")!!
            val b2 = block(task, type = "MAINTENANCE").text("blockerId")!!

            assertEquals("BLOCKED", resolve(task, b1).text("taskStatus"))
            val last = resolve(task, b2)

            assertEquals("IN_PROGRESS", last.text("taskStatus"))
            assertNull(reload(task).statusBeforeBlocked)
            assertEquals(BlockerResolutionType.RESOLVED, blockers.findById(UUID.fromString(b1)).orElseThrow().resolutionType)
        }

        @Test
        fun `returning to ASSIGNED keeps a valid assignee`() {
            val (_, task) = hotel.turnover(status = TaskStatus.ASSIGNED, assignee = hotel.housekeeper)
            val b = block(task).text("blockerId")!!
            assertEquals("ASSIGNED", resolve(task, b).text("taskStatus"))
            assertEquals(hotel.housekeeper.getId(), activeAssignment(task)?.staffId)
        }

        @Test
        fun `invalid assignee on resume ends the assignment and returns to PENDING, then reassign resumes the work`() {
            val off = offShiftHousekeeper()
            val (room, task) = hotel.turnover(
                RoomOperationalStatus.CLEANING, TaskStatus.BLOCKED, TaskStatus.IN_PROGRESS, started = true, assignee = off, blocked = true,
            )
            val firstStartedAt = reload(task).actualStartedAt
            val blockerId = activeBlockers(task).single().getId().toString()

            val resolved = resolve(task, blockerId)

            assertEquals("PENDING", resolved.text("taskStatus"))
            assertEquals("ASSIGNEE_INVALID_ON_RESUME", resolved.body.get("warnings").get(0).asString())
            assertNull(activeAssignment(task))
            assertEquals(UnassignedReason.ASSIGNEE_INVALID_ON_RESUME, assignments.findByTaskIdOrderByAssignedAtAsc(task.getId()).single().unassignedReason)
            assertEquals(RoomOperationalStatus.CLEANING, reload(room).operationalStatus, "room keeps its in-progress status (OPEN-5)")
            assertEquals(firstStartedAt, reload(task).actualStartedAt)

            assertEquals(200, assign(task, hotel.housekeeper).status)
            assertEquals(200, start(task).status)
            assertEquals(TaskStatus.IN_PROGRESS, reload(task).status)
            assertTrue(eventTypes(task.getId()).contains("TASK_RESUMED"))
        }

        @Test
        fun `invalid blocker input and terminal tasks are rejected`() {
            val (_, task) = hotel.turnover()
            assertProblem(block(task, type = "OTHER"), 400, "VALIDATION_ERROR")
            assertProblem(block(task, extra = mapOf("estimatedResolutionAt" to Instant.now().minusSeconds(60).toString())), 400, "VALIDATION_ERROR")
            val (_, done) = hotel.turnover(status = TaskStatus.COMPLETED)
            assertProblem(block(done), 409, "INVALID_STATE")
        }

        @Test
        fun `resolving an already resolved blocker is rejected`() {
            val (_, task) = hotel.turnover()
            val b1 = block(task).text("blockerId")!!
            block(task, type = "MAINTENANCE")
            resolve(task, b1)
            assertProblem(resolve(task, b1), 409, "INVALID_STATE")
        }
    }

    // ================================================================ complete

    @Nested
    inner class Complete {
        @Test
        fun `completing a cleaning returns the room to VACANT_DIRTY and creates the inspection`() {
            val (room, task) = hotel.turnover(RoomOperationalStatus.CLEANING, TaskStatus.IN_PROGRESS, started = true, assignee = hotel.housekeeper)

            val response = complete(task)

            assertEquals(200, response.status, "body=${response.body}")
            assertEquals(TaskStatus.COMPLETED, reload(task).status)
            assertEquals(RoomOperationalStatus.VACANT_DIRTY, reload(room).operationalStatus)
            assertEquals(UnassignedReason.TASK_COMPLETED, assignments.findByTaskIdOrderByAssignedAtAsc(task.getId()).single().unassignedReason)

            val inspection = tasks.findById(UUID.fromString(response.text("createdTaskId"))).orElseThrow()
            assertEquals(TaskType.INSPECTION, inspection.type)
            assertEquals(TaskOrigin.CLEANING_COMPLETED, inspection.origin)
            assertEquals(task.getId(), inspection.sourceTaskId)
            assertEquals(TaskStatus.PENDING, inspection.status)
        }

        @Test
        fun `completing a stayover returns the room to OCCUPIED without inspection`() {
            val (room, reservation) = hotel.stay(RoomOperationalStatus.STAYOVER_CLEANING)
            val task = hotel.stayoverTask(room, reservation, TaskStatus.IN_PROGRESS, started = true, assigned = true)
            val response = complete(task)
            assertEquals(200, response.status)
            assertNull(response.text("createdTaskId"))
            assertEquals(RoomOperationalStatus.OCCUPIED, reload(room).operationalStatus)
        }

        @Test
        fun `inspection tasks cannot use complete, blocked tasks cannot complete, only the assignee completes`() {
            val (_, _, inspection) = inspectionReady()
            start(inspection, by = hotel.inspector)
            assertProblem(complete(inspection, by = hotel.inspector), 409, "INVALID_STATE")

            val (_, blocked) = hotel.turnover(RoomOperationalStatus.CLEANING, TaskStatus.BLOCKED, TaskStatus.IN_PROGRESS, started = true, assignee = hotel.housekeeper, blocked = true)
            assertProblem(complete(blocked), 409, "ACTIVE_BLOCKER_EXISTS")

            val (_, running) = hotel.turnover(RoomOperationalStatus.CLEANING, TaskStatus.IN_PROGRESS, started = true, assignee = hotel.housekeeper)
            assertProblem(complete(running, by = hotel.manager), 403, "FORBIDDEN")
        }
    }

    // ===================================================== cancel (05 matrix)

    @Nested
    inner class Cancel {
        @Test
        fun `cancel before start leaves the room unchanged`() {
            val (room, task) = hotel.turnover(status = TaskStatus.ASSIGNED, assignee = hotel.housekeeper)
            assertEquals(200, cancel(task).status)
            assertEquals(TaskStatus.CANCELLED, reload(task).status)
            assertEquals(RoomOperationalStatus.VACANT_DIRTY, reload(room).operationalStatus)
            assertEquals(UnassignedReason.TASK_CANCELLED, assignments.findByTaskIdOrderByAssignedAtAsc(task.getId()).single().unassignedReason)
        }

        @Test
        fun `cancel after start returns the room per matrix`() {
            val (cleaningRoom, cleaning) = hotel.turnover(RoomOperationalStatus.CLEANING, TaskStatus.IN_PROGRESS, started = true, assignee = hotel.housekeeper)
            assertEquals(200, cancel(cleaning).status)
            assertEquals(RoomOperationalStatus.VACANT_DIRTY, reload(cleaningRoom).operationalStatus)

            val (inspectionRoom, _, inspection) = inspectionReady()
            start(inspection, by = hotel.inspector)
            assertEquals(200, cancel(inspection).status)
            assertEquals(RoomOperationalStatus.VACANT_DIRTY, reload(inspectionRoom).operationalStatus, "inspection cancel must never yield READY")

            val (stayRoom, reservation) = hotel.stay(RoomOperationalStatus.STAYOVER_CLEANING)
            val stayover = hotel.stayoverTask(stayRoom, reservation, TaskStatus.IN_PROGRESS, started = true, assigned = true)
            assertEquals(200, cancel(stayover).status)
            assertEquals(RoomOperationalStatus.OCCUPIED, reload(stayRoom).operationalStatus)
        }

        @Test
        fun `started task cancel requires a manager even when it is blocked (N3)`() {
            val (room, reservation) = hotel.stay(RoomOperationalStatus.STAYOVER_CLEANING)
            val task = hotel.stayoverTask(room, reservation, TaskStatus.BLOCKED, TaskStatus.IN_PROGRESS, started = true, assigned = true, blocked = true)

            assertProblem(cancel(task, by = hotel.frontDesk), 403, "FORBIDDEN")
            assertProblem(cancel(task, by = hotel.housekeeper), 403, "FORBIDDEN")
            assertEquals(200, cancel(task, by = hotel.manager).status)
            assertTrue(activeBlockers(task).isEmpty())
            assertEquals(BlockerResolutionType.TASK_CANCELLED, blockers.findByTaskIdOrderByBlockedAtAsc(task.getId()).single().resolutionType)
            assertEquals(RoomOperationalStatus.OCCUPIED, reload(room).operationalStatus)
        }

        @Test
        fun `front desk may cancel only unstarted stayovers`() {
            val (room, reservation) = hotel.stay()
            val stayover = hotel.stayoverTask(room, reservation, TaskStatus.PENDING)
            assertEquals(200, cancel(stayover, by = hotel.frontDesk).status)

            val (_, cleaning) = hotel.turnover()
            assertProblem(cancel(cleaning, by = hotel.frontDesk), 403, "FORBIDDEN")
        }

        @Test
        fun `reason is required and terminal tasks cannot be cancelled`() {
            val (_, task) = hotel.turnover()
            assertProblem(cancel(task, reason = " "), 400, "VALIDATION_ERROR")
            val (_, done) = hotel.turnover(status = TaskStatus.COMPLETED)
            assertProblem(cancel(done), 409, "INVALID_STATE")
        }
    }

    // ========================================================== end-to-end

    @Test
    fun `checkout to inspection - assign, start, block, resolve, complete`() {
        val (room, reservation) = hotel.stay()
        val checkout = api.post("/api/v1/reservations/${reservation.getId()}/check-out", staffId = hotel.frontDesk.getId(), idempotencyKey = key())
        val cleaning = tasks.findById(UUID.fromString(checkout.text("checkoutCleaningTaskId"))).orElseThrow()

        assertEquals(200, assign(cleaning, hotel.housekeeper).status)
        assertEquals(200, start(cleaning).status)
        val blocker = block(cleaning, by = hotel.housekeeper).text("blockerId")!!
        assertEquals("IN_PROGRESS", resolve(cleaning, blocker, by = hotel.housekeeper).text("taskStatus"))
        val done = complete(cleaning)

        assertEquals(200, done.status, "body=${done.body}")
        assertEquals(RoomOperationalStatus.VACANT_DIRTY, reload(room).operationalStatus)
        assertNotNull(done.text("createdTaskId"))
        assertEquals(
            listOf("HOUSEKEEPING_TASK_CREATED", "TASK_ASSIGNED", "TASK_STARTED", "BLOCKER_ADDED", "BLOCKER_RESOLVED", "TASK_COMPLETED"),
            eventTypes(cleaning.getId()),
        )
    }

    // ============================================================ concurrency

    @Nested
    inner class Concurrency {
        @Test
        fun `two concurrent starts of the same task start it exactly once`() {
            val (_, task) = hotel.turnover(status = TaskStatus.ASSIGNED, assignee = hotel.housekeeper)
            val results = runConcurrently(2) { start(task) }
            assertTrue(results.all { it.status == 200 }, "bodies=${results.map { it.body }}")
            assertEquals(setOf(false, true), results.map { it.body.get("alreadyStarted").asBoolean() }.toSet())
            assertEquals(listOf("TASK_STARTED"), eventTypes(task.getId()))
        }

        /**
         * P6 회귀: command 시각을 잠금 대기 전에 정하면, 늦게 잠금을 얻은 재배정이 앞선 배정보다 과거 시각으로
         * 종료를 기록해 `assignment_interval_valid` 위반(500)이 났다. 시각은 잠금 이후에 정해져야 한다.
         */
        @Test
        fun `concurrent reassignments record a non-decreasing assignment history`() {
            val (_, task) = hotel.turnover()
            val other = hotel.staff(StaffRole.HOUSEKEEPER, "HK-02").also { hotel.onShift(it) }
            repeat(5) { round ->
                val results = runConcurrently(2) { i -> assign(task, if (i == 0) hotel.housekeeper else other) }
                assertTrue(results.all { it.status == 200 }, "round $round bodies=${results.map { it.body }}")
            }
            val history = assignments.findByTaskIdOrderByAssignedAtAsc(task.getId())
            history.filter { !it.isActive }.forEach { assertTrue(!it.unassignedAt!!.isBefore(it.assignedAt), "interval of ${it.getId()}") }
            history.zipWithNext().forEach { (a, b) -> assertTrue(!b.assignedAt.isBefore(a.unassignedAt ?: a.assignedAt)) }
            assertEquals(1, history.count { it.isActive })
        }

        @Test
        fun `stayover start racing checkout ends in one consistent outcome`() {
            val (room, reservation) = hotel.stay()
            val task = hotel.stayoverTask(room, reservation, TaskStatus.ASSIGNED, assigned = true)

            val (startResult, checkoutResult) = runConcurrently(2) { i ->
                if (i == 0) start(task) else api.post("/api/v1/reservations/${reservation.getId()}/check-out", staffId = hotel.frontDesk.getId(), idempotencyKey = key())
            }

            val finalTask = reload(task)
            val finalReservation = reservations.findById(reservation.getId()).orElseThrow()
            if (startResult.status == 200) {
                assertProblem(checkoutResult, 409, "STAYOVER_IN_PROGRESS")
                assertEquals(TaskStatus.IN_PROGRESS, finalTask.status)
                assertEquals(ReservationStatus.CHECKED_IN, finalReservation.status)
                assertEquals(RoomOperationalStatus.STAYOVER_CLEANING, reload(room).operationalStatus)
            } else {
                assertEquals(200, checkoutResult.status, "body=${checkoutResult.body}")
                assertProblem(startResult, 409, "INVALID_STATE")
                assertEquals(TaskStatus.CANCELLED, finalTask.status)
                assertEquals(ReservationStatus.CHECKED_OUT, finalReservation.status)
                assertEquals(RoomOperationalStatus.VACANT_DIRTY, reload(room).operationalStatus)
            }
        }

        @Test
        fun `start racing a blocker keeps the invariants in either order`() {
            val (room, task) = hotel.turnover(status = TaskStatus.ASSIGNED, assignee = hotel.housekeeper)
            val (startResult, blockResult) = runConcurrently(2) { i -> if (i == 0) start(task) else block(task, by = hotel.housekeeper) }

            assertEquals(201, blockResult.status, "body=${blockResult.body}")
            val finalTask = reload(task)
            assertEquals(TaskStatus.BLOCKED, finalTask.status)
            if (startResult.status == 200) {
                assertEquals(TaskStatus.IN_PROGRESS, finalTask.statusBeforeBlocked)
                assertNotNull(finalTask.actualStartedAt)
                assertEquals(RoomOperationalStatus.CLEANING, reload(room).operationalStatus)
            } else {
                assertProblem(startResult, 409, "ACTIVE_BLOCKER_EXISTS")
                assertEquals(TaskStatus.ASSIGNED, finalTask.statusBeforeBlocked)
                assertNull(finalTask.actualStartedAt)
                assertEquals(RoomOperationalStatus.VACANT_DIRTY, reload(room).operationalStatus)
            }
        }
    }

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
