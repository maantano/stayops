package com.stayops.room

import com.stayops.TestcontainersConfiguration
import com.stayops.housekeeping.domain.DurationSource
import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.InspectionResult
import com.stayops.housekeeping.domain.TaskOrigin
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.housekeeping.domain.UnassignedReason
import com.stayops.housekeeping.infrastructure.HousekeepingTaskRepository
import com.stayops.housekeeping.infrastructure.InspectionRecordRepository
import com.stayops.housekeeping.infrastructure.TaskAssignmentRepository
import com.stayops.operations.infrastructure.OperationalEventRepository
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
 * P5: 검사 결과·재청소, 투숙 중 청소 요청, 판매 중단(OOS), 관리자 재정비, workflowStage
 * (docs/v4.1/02 §2.10·§4·§5, 05 §3 Room/Availability·Stayover·Rework·workflowStage, §8 E2E).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class RoomWorkflowTests(
    @Autowired private val json: JsonMapper,
    @Autowired private val world: StayWorld,
    @Autowired private val tasks: HousekeepingTaskRepository,
    @Autowired private val rooms: RoomRepository,
    @Autowired private val reservations: ReservationRepository,
    @Autowired private val assignments: TaskAssignmentRepository,
    @Autowired private val inspectionRecords: InspectionRecordRepository,
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

    // ---------------------------------------------------------------- helpers

    private fun key() = UUID.randomUUID().toString()
    private fun taskPath(id: Any, suffix: String) = "/api/v1/tasks/$id$suffix"

    private fun assign(taskId: Any, staff: Staff) = api.post(taskPath(taskId, "/assignments"), mapOf("staffId" to staff.getId()), hotel.manager.getId(), key())
    private fun start(taskId: Any, by: Staff) = api.post(taskPath(taskId, "/start"), staffId = by.getId(), idempotencyKey = key())
    private fun complete(taskId: Any, by: Staff) = api.post(taskPath(taskId, "/complete"), staffId = by.getId(), idempotencyKey = key())
    private fun inspect(taskId: Any, result: String, reason: String? = null, by: Staff = hotel.inspector, key: String = key()) =
        api.post(taskPath(taskId, "/inspection-result"), mapOf("result" to result, "failureReason" to reason, "checklist" to mapOf("bathroom" to (result == "PASSED"))), by.getId(), key)
    private fun stayover(room: Room, by: Staff = hotel.frontDesk, body: Map<String, Any?> = mapOf("notes" to "고객 요청"), key: String = key()) =
        api.post("/api/v1/rooms/${room.getId()}/stayover-cleanings", body, by.getId(), key)
    private fun rework(room: Room, kind: String, by: Staff = hotel.manager, extra: Map<String, Any?> = emptyMap(), key: String = key()) =
        api.post("/api/v1/rooms/${room.getId()}/rework-tasks", mapOf("kind" to kind, "reason" to "재정비 필요") + extra, by.getId(), key)
    private fun availability(room: Room, status: String, reason: String? = "에어컨 고장", by: Staff = hotel.manager) =
        api.post("/api/v1/rooms/${room.getId()}/availability", mapOf("status" to status, "reason" to reason), by.getId(), key())
    private fun roomView(room: Room, by: Staff = hotel.frontDesk) = api.get("/api/v1/properties/${hotel.property.getId()}/rooms/${room.getId()}", by.getId())
    private fun stage(room: Room) = roomView(room).text("workflowStage")

    private fun reload(r: Room) = rooms.findById(r.getId()).orElseThrow()
    private fun reload(t: HousekeepingTask) = tasks.findById(t.getId()).orElseThrow()
    private fun task(id: String?) = tasks.findById(UUID.fromString(id)).orElseThrow()
    private fun eventTypes(id: UUID) = events.findByAggregateIdOrderByOccurredAtAscIdAsc(id).map { it.eventType }

    /** 완료된 청소 + 진행 중 검사 (객실 INSPECTING). */
    private fun inspectionInProgress(): Pair<Room, HousekeepingTask> {
        val (room, cleaning) = hotel.turnover(RoomOperationalStatus.INSPECTING, TaskStatus.COMPLETED)
        val inspection = hotel.task(room, TaskType.INSPECTION, TaskStatus.IN_PROGRESS, source = cleaning, started = true, assignee = hotel.inspector)
        return room to inspection
    }

    // ======================================================= inspection result

    @Nested
    inner class InspectionResults {
        @Test
        fun `PASSED makes the room READY and completes the inspection`() {
            val (room, inspection) = inspectionInProgress()
            val response = inspect(inspection.getId(), "PASSED")

            assertEquals(200, response.status, "body=${response.body}")
            assertEquals(RoomOperationalStatus.READY, reload(room).operationalStatus)
            assertEquals(TaskStatus.COMPLETED, reload(inspection).status)
            assertNull(response.text("createdTaskId"))
            val record = assertNotNull(inspectionRecords.findByTaskId(inspection.getId()))
            assertEquals(InspectionResult.PASSED, record.result)
            assertTrue(record.checklistSnapshotJson.contains("bathroom"))
            assertEquals(UnassignedReason.TASK_COMPLETED, assignments.findByTaskIdOrderByAssignedAtAsc(inspection.getId()).single().unassignedReason)
            assertEquals(listOf("INSPECTION_RECORDED"), eventTypes(inspection.getId()))
        }

        @Test
        fun `FAILED returns the room to VACANT_DIRTY and creates a re-cleaning from the failed inspection`() {
            val (room, inspection) = inspectionInProgress()
            val response = inspect(inspection.getId(), "FAILED", "욕실 바닥 물기 잔존")

            assertEquals(200, response.status, "body=${response.body}")
            assertEquals(RoomOperationalStatus.VACANT_DIRTY, reload(room).operationalStatus)
            val reCleaning = task(response.text("createdTaskId"))
            assertEquals(TaskType.RE_CLEANING, reCleaning.type)
            assertEquals(TaskOrigin.INSPECTION_FAILED, reCleaning.origin)
            assertEquals(inspection.getId(), reCleaning.sourceTaskId)
            assertEquals("욕실 바닥 물기 잔존", inspectionRecords.findByTaskId(inspection.getId())!!.failureReason)
            assertEquals("NEEDS_CLEANING", stage(room))
        }

        @Test
        fun `FAILED without a reason changes nothing`() {
            val (room, inspection) = inspectionInProgress()
            assertProblem(inspect(inspection.getId(), "FAILED", " "), 400, "VALIDATION_ERROR")
            assertEquals(RoomOperationalStatus.INSPECTING, reload(room).operationalStatus)
            assertNull(inspectionRecords.findByTaskId(inspection.getId()))
        }

        @Test
        fun `result requires an in-progress inspection by its inspector and cannot be submitted twice`() {
            val (_, inspection) = inspectionInProgress()
            assertProblem(inspect(inspection.getId(), "PASSED", by = hotel.manager), 403, "FORBIDDEN")
            assertEquals(200, inspect(inspection.getId(), "PASSED").status)
            assertProblem(inspect(inspection.getId(), "PASSED"), 409, "INVALID_STATE")

            val (_, cleaning) = hotel.turnover(RoomOperationalStatus.CLEANING, TaskStatus.IN_PROGRESS, started = true, assignee = hotel.housekeeper)
            assertProblem(inspect(cleaning.getId(), "PASSED", by = hotel.housekeeper), 409, "INVALID_STATE")
        }

        @Test
        fun `same key replays the result without a second record`() {
            val (_, inspection) = inspectionInProgress()
            val first = inspect(inspection.getId(), "PASSED", key = "insp-1")
            val replay = inspect(inspection.getId(), "PASSED", key = "insp-1")
            assertEquals(first.body, replay.body)
            assertEquals("true", replay.header("Idempotency-Replayed"))
        }
    }

    // ============================================================ stayover

    @Nested
    inner class Stayover {
        @Test
        fun `request on an occupied room creates a pending stayover and keeps the room OCCUPIED`() {
            val (room, reservation) = hotel.stay()
            val response = stayover(room, body = mapOf("estimatedDurationMinutes" to 20, "notes" to "수건 교체 요청"))

            assertEquals(201, response.status, "body=${response.body}")
            val task = task(response.text("taskId"))
            assertEquals(TaskType.STAYOVER_CLEANING, task.type)
            assertEquals(TaskStatus.PENDING, task.status)
            assertEquals(TaskOrigin.STAYOVER_REQUEST, task.origin)
            assertEquals(reservation.getId(), task.reservationId)
            assertEquals(DurationSource.USER_INPUT, task.durationSource)
            assertEquals("수건 교체 요청", task.notes)
            assertEquals(RoomOperationalStatus.OCCUPIED, reload(room).operationalStatus)
        }

        @Test
        fun `duplicate active stayover is rejected with the existing task`() {
            val (room, _) = hotel.stay()
            val first = stayover(room)
            val second = stayover(room)
            assertProblem(second, 409, "ACTIVE_WORKFLOW_EXISTS")
            assertEquals(first.text("taskId"), second.text("existingTaskId"))
        }

        @Test
        fun `request is rejected for vacant, out-of-service, unauthorized or past-scheduled cases`() {
            assertProblem(stayover(hotel.room(RoomOperationalStatus.READY)), 409, "INVALID_STATE")

            val (oosRoom, _) = hotel.stay()
            availability(oosRoom, "OUT_OF_SERVICE")
            assertProblem(stayover(oosRoom), 409, "ROOM_NOT_AVAILABLE")

            val (room, _) = hotel.stay()
            assertProblem(stayover(room, by = hotel.housekeeper), 403, "FORBIDDEN")
            assertProblem(stayover(room, body = mapOf("scheduledAt" to Instant.now().minus(Duration.ofHours(1)).toString())), 400, "VALIDATION_ERROR")
        }

        @Test
        fun `E2E 10 - stayover create, start, block, resolve, complete keeps the stay`() {
            val (room, reservation) = hotel.stay()
            val taskId = stayover(room).text("taskId")!!
            assertEquals(200, assign(taskId, hotel.housekeeper).status)
            assertEquals(200, start(taskId, hotel.housekeeper).status)
            assertEquals("STAYOVER_CLEANING", stage(room))
            val blockerId = api.post(taskPath(taskId, "/blockers"), mapOf("type" to "DND"), hotel.housekeeper.getId(), key()).text("blockerId")
            assertEquals("STAYOVER_CLEANING", reload(room).operationalStatus.name)
            api.post(taskPath(taskId, "/blockers/$blockerId/resolve"), null, hotel.housekeeper.getId(), key())
            assertEquals(200, complete(taskId, hotel.housekeeper).status)

            assertEquals(RoomOperationalStatus.OCCUPIED, reload(room).operationalStatus)
            assertEquals(ReservationStatus.CHECKED_IN, reservations.findById(reservation.getId()).orElseThrow().status)
        }

        @Test
        fun `stayover request racing checkout leaves no orphan stayover`() {
            val (room, reservation) = hotel.stay()
            val (create, checkout) = runConcurrently(2) { i ->
                if (i == 0) stayover(room) else api.post("/api/v1/reservations/${reservation.getId()}/check-out", staffId = hotel.frontDesk.getId(), idempotencyKey = key())
            }
            assertEquals(200, checkout.status, "checkout must succeed in either order: ${checkout.body}")
            val stayovers = tasks.findByRoomIdOrderByCreatedAtAsc(room.getId()).filter { it.type == TaskType.STAYOVER_CLEANING }
            if (create.status == 201) {
                assertEquals(TaskStatus.CANCELLED, stayovers.single().status, "created first, then auto-cancelled by checkout")
            } else {
                // checkout이 먼저면 객실에 체크아웃 청소가 생기므로 "진행 중 작업 있음"이 먼저 판정된다 (02 §4 Stayover create 판정 순서).
                // 로컬에서는 거의 항상 요청이 먼저 처리돼 이 분기가 실행되지 않았고, CI에서 처음 드러났다 → 아래 순서 고정 테스트로 매번 확인한다.
                assertProblem(create, 409, "ACTIVE_WORKFLOW_EXISTS")
                assertTrue(stayovers.isEmpty())
            }
            assertEquals(RoomOperationalStatus.VACANT_DIRTY, reload(room).operationalStatus)
        }

        @Test
        fun `a stayover request after checkout is refused because the checkout cleaning is the room's active task`() {
            val (room, reservation) = hotel.stay()
            val checkout = api.post("/api/v1/reservations/${reservation.getId()}/check-out", staffId = hotel.frontDesk.getId(), idempotencyKey = key())
            assertEquals(200, checkout.status, "body=${checkout.body}")

            val late = stayover(room)
            assertProblem(late, 409, "ACTIVE_WORKFLOW_EXISTS")
            assertEquals(checkout.body.get("checkoutCleaningTaskId").asString(), late.body.get("existingTaskId").asString())
            assertTrue(tasks.findByRoomIdOrderByCreatedAtAsc(room.getId()).none { it.type == TaskType.STAYOVER_CLEANING })
        }
    }

    // ========================================================= availability

    @Nested
    inner class Availability {
        @Test
        fun `out of service keeps the operational status and repeated requests are no-ops`() {
            val room = hotel.room(RoomOperationalStatus.READY)
            val first = availability(room, "OUT_OF_SERVICE")
            val again = availability(room, "OUT_OF_SERVICE")

            assertEquals(200, first.status, "body=${first.body}")
            assertEquals(true, first.body.get("changed").asBoolean())
            assertEquals(false, again.body.get("changed").asBoolean())
            assertEquals(RoomAvailabilityStatus.OUT_OF_SERVICE, reload(room).availabilityStatus)
            assertEquals(RoomOperationalStatus.READY, reload(room).operationalStatus)
            assertEquals(listOf("ROOM_AVAILABILITY_CHANGED"), eventTypes(room.getId()))
        }

        @Test
        fun `restoring availability does not make a dirty room READY`() {
            val room = hotel.room(RoomOperationalStatus.VACANT_DIRTY)
            availability(room, "OUT_OF_SERVICE")
            val restored = availability(room, "AVAILABLE", "수리 완료")
            assertEquals(200, restored.status)
            assertEquals(RoomAvailabilityStatus.AVAILABLE, reload(room).availabilityStatus)
            assertNull(reload(room).availabilityReason)
            assertEquals(RoomOperationalStatus.VACANT_DIRTY, reload(room).operationalStatus)
        }

        @Test
        fun `out of service does not check out or cancel an existing stay`() {
            val (room, reservation) = hotel.stay()
            availability(room, "OUT_OF_SERVICE")
            assertEquals(ReservationStatus.CHECKED_IN, reservations.findById(reservation.getId()).orElseThrow().status)
            assertEquals(RoomOperationalStatus.OCCUPIED, reload(room).operationalStatus)
        }

        @Test
        fun `only managers change availability and a reason is required`() {
            val room = hotel.room()
            assertProblem(availability(room, "OUT_OF_SERVICE", by = hotel.frontDesk), 403, "FORBIDDEN")
            assertProblem(availability(room, "OUT_OF_SERVICE", reason = null), 400, "VALIDATION_ERROR")
        }
    }

    // =============================================================== rework

    @Nested
    inner class Rework {
        @Test
        fun `cleaning rework on a VACANT_DIRTY room creates a manager re-cleaning without changing the room`() {
            val (room, _) = hotel.turnover(status = TaskStatus.CANCELLED, started = true) // 시작 후 취소된 청소
            val response = rework(room, "CLEANING")

            assertEquals(201, response.status, "body=${response.body}")
            val task = task(response.text("taskId"))
            assertEquals(TaskType.RE_CLEANING, task.type)
            assertEquals(TaskOrigin.MANAGER_REWORK, task.origin)
            assertEquals("재정비 필요", task.originReason)
            assertNull(task.sourceTaskId)
            assertEquals(DurationSource.CONFIG_DEFAULT, task.durationSource)
            assertEquals(30, task.estimatedDurationMinutes)
            assertEquals(RoomOperationalStatus.VACANT_DIRTY, reload(room).operationalStatus)
        }

        @Test
        fun `inspection rework after a cancelled inspection verifies the same completed cleaning`() {
            val (room, cleaning) = hotel.turnover(status = TaskStatus.COMPLETED)
            hotel.task(room, TaskType.INSPECTION, TaskStatus.CANCELLED, source = cleaning, started = true)
            assertEquals("AWAITING_INSPECTION", stage(room))

            val response = rework(room, "INSPECTION")

            assertEquals(201, response.status, "body=${response.body}")
            assertEquals(cleaning.getId(), task(response.text("taskId")).sourceTaskId)
            assertEquals("AWAITING_INSPECTION", stage(room))
        }

        @Test
        fun `inspection rework is rejected after a partial cleaning or an already inspected cleaning`() {
            val (partialRoom, _) = hotel.turnover(status = TaskStatus.CANCELLED, started = true)
            val partial = rework(partialRoom, "INSPECTION")
            assertProblem(partial, 409, "INVALID_STATE")
            assertEquals("NOT_AWAITING_INSPECTION", partial.text("reason"))

            val (inspectedRoom, cleaning) = hotel.turnover(status = TaskStatus.COMPLETED)
            val failed = hotel.task(inspectedRoom, TaskType.INSPECTION, TaskStatus.COMPLETED, source = cleaning)
            hotel.inspectionRecord(failed, InspectionResult.FAILED)
            assertProblem(rework(inspectedRoom, "INSPECTION"), 409, "INVALID_STATE")
            assertEquals("NEEDS_CLEANING", stage(inspectedRoom))
        }

        @Test
        fun `rework is rejected on non-dirty rooms, with an active task, or for non-managers`() {
            assertProblem(rework(hotel.room(RoomOperationalStatus.READY), "CLEANING"), 409, "INVALID_STATE")

            val (room, pending) = hotel.turnover()
            val conflict = rework(room, "CLEANING")
            assertProblem(conflict, 409, "ACTIVE_WORKFLOW_EXISTS")
            assertEquals(pending.getId().toString(), conflict.text("existingTaskId"))

            val (free, _) = hotel.turnover(status = TaskStatus.CANCELLED)
            assertProblem(rework(free, "CLEANING", by = hotel.frontDesk), 403, "FORBIDDEN")
        }

        @Test
        fun `assignee can be set at creation, an invalid assignee creates nothing`() {
            val (room, _) = hotel.turnover(status = TaskStatus.CANCELLED)
            val invalid = rework(room, "CLEANING", extra = mapOf("assigneeStaffId" to hotel.inspector.getId()))
            assertProblem(invalid, 409, "ASSIGNMENT_INVALID")
            assertTrue(tasks.findByRoomIdOrderByCreatedAtAsc(room.getId()).none { it.origin == TaskOrigin.MANAGER_REWORK })

            val valid = rework(room, "CLEANING", extra = mapOf("assigneeStaffId" to hotel.housekeeper.getId(), "estimatedDurationMinutes" to 50))
            assertEquals(201, valid.status, "body=${valid.body}")
            assertEquals("ASSIGNED", valid.text("taskStatus"))
            assertEquals(DurationSource.USER_INPUT, task(valid.text("taskId")).durationSource)
        }

        @Test
        fun `retries and concurrent requests create exactly one rework task`() {
            val (room, _) = hotel.turnover(status = TaskStatus.CANCELLED)
            val first = rework(room, "CLEANING", key = "rw-1")
            val replay = rework(room, "CLEANING", key = "rw-1")
            assertEquals(first.body, replay.body)

            val (room2, _) = hotel.turnover(status = TaskStatus.CANCELLED)
            val results = runConcurrently(2) { rework(room2, "CLEANING") }
            assertEquals(listOf(201, 409), results.map { it.status }.sorted())
            assertEquals(1, tasks.findByRoomIdOrderByCreatedAtAsc(room2.getId()).count { it.origin == TaskOrigin.MANAGER_REWORK })
        }
    }

    // ======================================================== workflowStage

    @Nested
    inner class Stage {
        @Test
        fun `stage distinguishes dirty from cleaned-awaiting-inspection`() {
            val (dirty, _) = hotel.turnover()
            assertEquals("NEEDS_CLEANING", stage(dirty))

            val (awaiting, cleaning) = hotel.turnover(status = TaskStatus.COMPLETED)
            hotel.task(awaiting, TaskType.INSPECTION, TaskStatus.PENDING, source = cleaning)
            assertEquals("AWAITING_INSPECTION", stage(awaiting))
            assertEquals("VACANT_DIRTY", roomView(awaiting).text("operationalStatus"))
        }

        @Test
        fun `stage mirrors in-progress and occupied statuses`() {
            assertEquals("CLEANING", stage(hotel.room(RoomOperationalStatus.CLEANING)))
            assertEquals("INSPECTING", stage(hotel.room(RoomOperationalStatus.INSPECTING)))
            assertEquals("READY", stage(hotel.room(RoomOperationalStatus.READY)))
            assertEquals("OCCUPIED", stage(hotel.stay().first))
            assertEquals("STAYOVER_CLEANING", stage(hotel.stay(RoomOperationalStatus.STAYOVER_CLEANING).first))
        }

        @Test
        fun `started task without an assignee raises the interrupted-work alert`() {
            val (room, task) = hotel.turnover(RoomOperationalStatus.CLEANING, TaskStatus.PENDING, started = true)
            val view = roomView(room)
            assertEquals("WORK_INTERRUPTED_NO_ASSIGNEE", view.body.get("alerts").get(0).asString())
            assertEquals(task.getId().toString(), view.body.get("activeTask").get("taskId").asString())
        }

        @Test
        fun `room of another property is not found`() {
            val room = hotel.room()
            val other = world.newHotel()
            assertProblem(api.get("/api/v1/properties/${hotel.property.getId()}/rooms/${room.getId()}", other.frontDesk.getId()), 404, "NOT_FOUND")
        }
    }

    // =============================================================== E2E

    @Test
    fun `E2E 1-9 and 11 - checkout, clean, fail inspection, re-clean, pass, out-of-service blocks check-in, restore, check-in`() {
        val (room, stay) = hotel.stay()
        val checkout = api.post("/api/v1/reservations/${stay.getId()}/check-out", staffId = hotel.frontDesk.getId(), idempotencyKey = key())
        val cleaningId = checkout.text("checkoutCleaningTaskId")!!

        assign(cleaningId, hotel.housekeeper); start(cleaningId, hotel.housekeeper)
        val inspectionId = complete(cleaningId, hotel.housekeeper).text("createdTaskId")!!
        assertEquals("AWAITING_INSPECTION", stage(room))

        assign(inspectionId, hotel.inspector)
        assertEquals("AWAITING_INSPECTION", stage(room), "assignment alone must not move the room to INSPECTING")
        start(inspectionId, hotel.inspector)
        assertEquals("INSPECTING", stage(room))
        val reCleaningId = inspect(inspectionId, "FAILED", "침구 얼룩").text("createdTaskId")!!
        assertEquals("NEEDS_CLEANING", stage(room))

        assign(reCleaningId, hotel.housekeeper); start(reCleaningId, hotel.housekeeper)
        val reInspectionId = complete(reCleaningId, hotel.housekeeper).text("createdTaskId")!!
        assertEquals(UUID.fromString(reCleaningId), task(reInspectionId).sourceTaskId)
        assign(reInspectionId, hotel.inspector); start(reInspectionId, hotel.inspector)
        assertEquals(200, inspect(reInspectionId, "PASSED").status)
        assertEquals(RoomOperationalStatus.READY, reload(room).operationalStatus)

        val next = hotel.reservation(room, checkIn = Instant.now(), checkOut = Instant.now().plus(Duration.ofDays(1)))
        availability(room, "OUT_OF_SERVICE")
        assertProblem(api.post("/api/v1/reservations/${next.getId()}/check-in", staffId = hotel.frontDesk.getId(), idempotencyKey = key()), 409, "ROOM_NOT_AVAILABLE")
        availability(room, "AVAILABLE", "점검 완료")
        assertEquals(200, api.post("/api/v1/reservations/${next.getId()}/check-in", staffId = hotel.frontDesk.getId(), idempotencyKey = key()).status)
        assertEquals(RoomOperationalStatus.OCCUPIED, reload(room).operationalStatus)
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
