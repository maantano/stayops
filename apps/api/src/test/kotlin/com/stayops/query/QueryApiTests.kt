package com.stayops.query

import com.stayops.TestcontainersConfiguration
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.reservation.domain.ReservationStatus
import com.stayops.room.domain.RoomAvailabilityStatus
import com.stayops.room.domain.RoomOperationalStatus
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P8 조회 API (docs/v4.1/02 §6, 03 §3·§6, 04 §5·§10). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class QueryApiTests(
    @Autowired private val json: JsonMapper,
    @Autowired private val world: StayWorld,
    @LocalServerPort private val port: Int,
) {
    private lateinit var api: ApiClient
    private lateinit var hotel: StayWorld.Hotel

    @BeforeEach
    fun setUp() {
        api = ApiClient(port, json)
        hotel = world.newHotel()
    }

    private val base get() = "/api/v1/properties/${hotel.property.getId()}"
    private fun items(body: JsonNode): List<JsonNode> = (0 until body.get("items").size()).map { body.get("items").get(it) }
    private fun hours(h: Long): Instant = Instant.now().plus(Duration.ofHours(h))

    /** 모든 페이지를 따라가며 모은다(중복·누락 검사용). */
    private fun allPages(path: String, by: UUID, limit: Int): List<JsonNode> {
        val result = mutableListOf<JsonNode>()
        var cursor: String? = null
        var pages = 0
        do {
            val sep = if (path.contains('?')) '&' else '?'
            val response = api.get("$path${sep}limit=$limit" + (cursor?.let { "&cursor=$it" } ?: ""), by)
            assertEquals(200, response.status, "body=${response.body}")
            result += items(response.body)
            cursor = response.body.get("nextCursor").takeUnless { it.isNull }?.asString()
            pages++
        } while (cursor != null && pages < 50)
        return result
    }

    // ================================================================ rooms

    @Nested
    inner class Rooms {
        @Test
        fun `rooms page through with a stable order and no duplicates`() {
            repeat(5) { hotel.room() }
            val all = allPages("$base/rooms", hotel.frontDesk.getId(), limit = 2)
            assertEquals(5, all.size)
            assertEquals(5, all.map { it.get("roomId").asString() }.toSet().size)
            assertEquals(all.map { it.get("roomNumber").asString() }.sorted(), all.map { it.get("roomNumber").asString() })
        }

        @Test
        fun `rooms filter by workflow stage and are visible to housekeepers`() {
            hotel.room(RoomOperationalStatus.READY)
            hotel.turnover()
            val response = api.get("$base/rooms?stage=NEEDS_CLEANING", hotel.housekeeper.getId())
            assertEquals(200, response.status)
            assertEquals(listOf("NEEDS_CLEANING"), items(response.body).map { it.get("workflowStage").asString() })
        }

        @Test
        fun `invalid cursor and limit are validation errors`() {
            assertProblem(api.get("$base/rooms?cursor=not-a-cursor", hotel.frontDesk.getId()), 400, "VALIDATION_ERROR")
            assertProblem(api.get("$base/rooms?limit=500", hotel.frontDesk.getId()), 400, "VALIDATION_ERROR")
        }

        @Test
        fun `timeline lists room, task and reservation events newest first`() {
            val (room, reservation) = hotel.stay()
            api.post("/api/v1/reservations/${reservation.getId()}/check-out", staffId = hotel.frontDesk.getId())
            val all = allPages("$base/rooms/${room.getId()}/timeline", hotel.manager.getId(), limit = 2)

            assertEquals(setOf("RESERVATION_CHECKED_OUT", "ROOM_STATUS_CHANGED", "HOUSEKEEPING_TASK_CREATED"), all.map { it.get("eventType").asString() }.toSet())
            val times = all.map { Instant.parse(it.get("occurredAt").asString()) }
            assertEquals(times.sortedDescending(), times)
            val roomEvent = all.single { it.get("eventType").asString() == "ROOM_STATUS_CHANGED" }
            assertEquals("VACANT_DIRTY", roomEvent.get("payload").get("to").asString())
            assertEquals("FD-01", roomEvent.get("actorDisplayName").asString())
        }
    }

    // ================================================================ tasks

    @Nested
    inner class Tasks {
        @Test
        fun `tasks filter by status and type and include assignee and active blockers`() {
            val (_, pending) = hotel.turnover()
            val (_, blocked) = hotel.turnover(status = TaskStatus.BLOCKED, statusBeforeBlocked = TaskStatus.ASSIGNED, assignee = hotel.housekeeper, blocked = true)
            hotel.turnover(status = TaskStatus.COMPLETED)

            val response = api.get("$base/tasks?status=PENDING&status=BLOCKED&type=CHECKOUT_CLEANING", hotel.manager.getId())

            assertEquals(200, response.status, "body=${response.body}")
            val rows = items(response.body)
            assertEquals(setOf(pending.getId().toString(), blocked.getId().toString()), rows.map { it.get("taskId").asString() }.toSet())
            val blockedRow = rows.single { it.get("taskId").asString() == blocked.getId().toString() }
            assertEquals("HK-01", blockedRow.get("assignee").get("displayName").asString())
            assertEquals("DND", blockedRow.get("activeBlockers").get(0).get("type").asString())
            assertEquals("ASSIGNED", blockedRow.get("statusBeforeBlocked").asString())
        }

        @Test
        fun `housekeepers only see their own tasks even if they ask for another staff`() {
            val other = hotel.staff(StaffRole.HOUSEKEEPER, "HK-02").also { hotel.onShift(it) }
            val (_, mine) = hotel.turnover(status = TaskStatus.ASSIGNED, assignee = hotel.housekeeper)
            hotel.turnover(status = TaskStatus.ASSIGNED, assignee = other)
            hotel.turnover()

            val response = api.get("$base/tasks?staffId=${other.getId()}", hotel.housekeeper.getId())
            assertEquals(listOf(mine.getId().toString()), items(response.body).map { it.get("taskId").asString() })
        }

        @Test
        fun `tasks page by scheduled time without duplicates`() {
            repeat(5) { i ->
                val room = hotel.room(RoomOperationalStatus.VACANT_DIRTY)
                hotel.task(room, TaskType.RE_CLEANING, TaskStatus.PENDING, scheduledAt = hours(i.toLong()))
            }
            val all = allPages("$base/tasks", hotel.manager.getId(), limit = 2)
            assertEquals(5, all.size)
            val times = all.map { Instant.parse(it.get("scheduledAt").asString()) }
            assertEquals(times.sorted(), times)
        }
    }

    // ========================================================= reservations

    @Nested
    inner class Reservations {
        @Test
        fun `reservations filter by status and check-in window, front desk only`() {
            val room = hotel.room()
            val soon = hotel.reservation(room, checkIn = hours(2), checkOut = hours(26))
            hotel.reservation(room, checkIn = hours(48), checkOut = hours(72))
            hotel.reservation(hotel.room(), ReservationStatus.CANCELLED, checkIn = hours(3), checkOut = hours(27))

            val response = api.get("$base/reservations?status=CONFIRMED&checkInFrom=${Instant.now()}&checkInTo=${hours(24)}", hotel.frontDesk.getId())
            assertEquals(200, response.status, "body=${response.body}")
            assertEquals(listOf(soon.getId().toString()), items(response.body).map { it.get("reservationId").asString() })
            assertProblem(api.get("$base/reservations", hotel.housekeeper.getId()), 403, "FORBIDDEN")
        }
    }

    // ============================================================ workloads

    @Nested
    inner class Workloads {
        @Test
        fun `workload ratio is assigned estimate over available shift minutes`() {
            hotel.turnover(status = TaskStatus.ASSIGNED, assignee = hotel.housekeeper) // 25분 (fixture)
            hotel.turnover(status = TaskStatus.ASSIGNED, assignee = hotel.housekeeper)
            val noShift = hotel.staff(StaffRole.HOUSEKEEPER, "HK-NOSHIFT")

            val response = api.get("$base/staff-workloads", hotel.manager.getId())
            assertEquals(200, response.status, "body=${response.body}")
            val rows = (0 until response.body.get("staff").size()).map { response.body.get("staff").get(it) }
            val hk = rows.single { it.get("displayName").asString() == "HK-01" }
            assertEquals(2, hk.get("activeTaskCount").asInt())
            assertEquals(50, hk.get("assignedEstimatedMinutes").asLong())
            val available = hk.get("availableMinutes").asLong()
            assertTrue(available > 0)
            assertEquals(50.0 / available, hk.get("workloadRatio").asDouble(), 1e-9)
            val none = rows.single { it.get("staffId").asString() == noShift.getId().toString() }
            assertTrue(none.get("workloadRatio").isNull)
            assertEquals("NO_SHIFT", none.get("flags").get(0).asString())
            assertTrue(rows.none { it.get("role").asString() == "MANAGER" })
        }
    }

    // ============================================================ dashboard

    @Nested
    inner class DashboardTests {
        @Test
        fun `dashboard aggregates rooms, stays, tasks and readiness in property time`() {
            val (_, stay) = hotel.stay()
            api.post("/api/v1/reservations/${stay.getId()}/check-out", staffId = hotel.frontDesk.getId())
            val ready = hotel.room(RoomOperationalStatus.READY, RoomAvailabilityStatus.OUT_OF_SERVICE)
            hotel.reservation(ready, checkIn = hours(1), checkOut = hours(25))
            // 체크아웃 이력 없이 청소 대기 객실을 만든다(turnover fixture의 과거 체크아웃은 자정 근처에서 '오늘' 집계를 흔든다).
            val late = hotel.room(RoomOperationalStatus.VACANT_DIRTY)
            hotel.task(late, TaskType.RE_CLEANING, TaskStatus.PENDING)
            hotel.reservation(late, checkIn = Instant.now().plus(Duration.ofMinutes(40)), checkOut = hours(24))

            val response = api.get("$base/dashboard", hotel.frontDesk.getId())

            assertEquals(200, response.status, "body=${response.body}")
            val body = response.body
            assertEquals(true, body.get("syntheticData").asBoolean())
            assertEquals("Asia/Seoul", body.get("timezone").asString())
            assertEquals(3, body.get("rooms").get("total").asInt())
            assertEquals(2, body.get("rooms").get("byStage").get("NEEDS_CLEANING").asInt())
            assertEquals(1, body.get("rooms").get("byAvailability").get("OUT_OF_SERVICE").asInt())
            assertEquals(1, body.get("stays").get("checkedOutToday").asInt())
            assertEquals(2, body.get("tasks").get("PENDING").asInt())
            val risk = body.get("readiness").get("topRisks").get(0)
            assertEquals(late.roomNumber, risk.get("roomNumber").asString())
            assertEquals("AT_RISK", risk.get("grade").asString())
            assertEquals("CLEANING", risk.get("topReasons").get(0).asString())
            assertEquals(1, body.get("readiness").get("summary").get("READY").asInt())
        }

        @Test
        fun `dashboard and readiness are for front desk and managers only`() {
            assertProblem(api.get("$base/dashboard", hotel.housekeeper.getId()), 403, "FORBIDDEN")
            assertProblem(api.get("$base/readiness", hotel.inspector.getId()), 403, "FORBIDDEN")
            assertProblem(api.get("$base/dashboard", world.newHotel().manager.getId()), 404, "NOT_FOUND")
        }
    }

    // ================================================================= CORS

    @Test
    fun `CORS preflight allows the web origin and exposes correlation headers`() {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$base/rooms"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "x-demo-staff-id,idempotency-key")
            .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())
        assertEquals("http://localhost:3000", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))

        val evil = HttpRequest.newBuilder(URI.create("http://localhost:$port$base/rooms"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", "https://evil.example")
            .header("Access-Control-Request-Method", "GET")
            .build()
        assertNull(HttpClient.newHttpClient().send(evil, HttpResponse.BodyHandlers.discarding()).headers().firstValue("Access-Control-Allow-Origin").orElse(null))
    }
}
