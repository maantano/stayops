package com.stayops.readiness

import com.stayops.TestcontainersConfiguration
import com.stayops.housekeeping.domain.BlockerType
import com.stayops.housekeeping.domain.TaskBlocker
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.infrastructure.TaskBlockerRepository
import com.stayops.reservation.domain.ReservationStatus
import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.support.ApiClient
import com.stayops.support.ApiClient.Companion.assertProblem
import com.stayops.support.StayWorld
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** GET /properties/{id}/readiness: DB 상태로 스냅샷을 만들어 계산하는지 (docs/v4.1/02 §8, 05 §5). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class ReadinessApiTests(
    @Autowired private val json: JsonMapper,
    @Autowired private val world: StayWorld,
    @Autowired private val blockers: TaskBlockerRepository,
    @LocalServerPort private val port: Int,
) {
    private lateinit var api: ApiClient
    private lateinit var hotel: StayWorld.Hotel

    @BeforeEach
    fun setUp() {
        api = ApiClient(port, json)
        hotel = world.newHotel()
    }

    private fun hours(h: Long): Instant = Instant.now().plus(Duration.ofHours(h))
    private fun minutes(m: Long): Instant = Instant.now().plus(Duration.ofMinutes(m))
    private fun readiness() = api.get("/api/v1/properties/${hotel.property.getId()}/readiness", hotel.frontDesk.getId())
    private fun rows(body: JsonNode): List<JsonNode> = (0 until body.get("rooms").size()).map { body.get("rooms").get(it) }
    private fun rowFor(body: JsonNode, roomNumber: String) = rows(body).single { it.get("roomNumber").asString() == roomNumber }

    @Test
    fun `evaluates each upcoming arrival from live state, most at-risk first`() {
        val ready = hotel.room(RoomOperationalStatus.READY)
        hotel.reservation(ready, checkIn = hours(3), checkOut = hours(27))

        val (dirty, _) = hotel.turnover() // 대기 중 체크아웃 청소 25분(fixture) + 검사 10분(기본값)
        hotel.reservation(dirty, checkIn = minutes(40), checkOut = hours(24))

        val occupied = hotel.room(RoomOperationalStatus.OCCUPIED)
        hotel.reservation(occupied, ReservationStatus.CHECKED_IN, checkIn = hours(-20), checkOut = hours(2))
        hotel.reservation(occupied, checkIn = hours(3), checkOut = hours(27))

        val noTask = hotel.room(RoomOperationalStatus.VACANT_DIRTY)
        hotel.reservation(noTask, checkIn = hours(5), checkOut = hours(29))

        hotel.room(RoomOperationalStatus.VACANT_DIRTY) // 도착 예정 없음 → 제외
        val farAway = hotel.room(RoomOperationalStatus.VACANT_DIRTY)
        hotel.reservation(farAway, checkIn = hours(30), checkOut = hours(50)) // 평가 시간창(24h) 밖 → 제외

        val response = readiness()

        assertEquals(200, response.status, "body=${response.body}")
        val body = response.body
        assertEquals(4, rows(body).size)
        assertEquals(listOf("AT_RISK", "AT_RISK", "UNKNOWN", "READY"), rows(body).map { it.get("grade").asString() })
        assertEquals(2, body.get("summary").get("AT_RISK").asInt())
        assertEquals("readiness-v1", body.get("calculationVersion").asString())
        assertEquals("Asia/Seoul", body.get("timezone").asString())

        // 청소 대기: 마감 = 40 − 30 = 10분 뒤, 예상 = 25 + 10 = 35분 뒤 → 약 25분 지연
        val dirtyRow = rowFor(body, dirty.roomNumber)
        assertTrue(dirtyRow.get("riskMinutes").asLong() in 25L..26L, "risk=${dirtyRow.get("riskMinutes")}") // 데이터 생성과 계산 사이 경과 시간 허용
        assertEquals(listOf("CLEANING", "INSPECTION"), (0 until dirtyRow.get("contributions").size()).map { dirtyRow.get("contributions").get(it).get("type").asString() })
        assertEquals("CHECKOUT_CLEANING", dirtyRow.get("inputSnapshot").get("activeTask").get("type").asString())
        assertConsistent(dirtyRow)

        // 투숙 중: 예정 체크아웃(2h)까지 대기 + 체크아웃 청소 45 + 검사 10 → 마감(3h − 30m)보다 25분 늦음
        val occupiedRow = rowFor(body, occupied.roomNumber)
        assertEquals("WAIT_FOR_CHECKOUT", occupiedRow.get("contributions").get(0).get("type").asString())
        assertTrue(occupiedRow.get("riskMinutes").asLong() in 25L..26L, "risk=${occupiedRow.get("riskMinutes")}")
        assertConsistent(occupiedRow)

        val unknownRow = rowFor(body, noTask.roomNumber)
        assertTrue(unknownRow.get("missingInputs").get(0).asString().contains("cleaning task missing"))
        assertTrue(unknownRow.get("expectedReadyAt").isNull)
    }

    @Test
    fun `blocker without an estimate makes the room UNKNOWN, adding an estimate makes it computable`() {
        val (room, task) = hotel.turnover(status = TaskStatus.BLOCKED, statusBeforeBlocked = TaskStatus.PENDING, blocked = true)
        hotel.reservation(room, checkIn = hours(4), checkOut = hours(28))

        assertEquals("UNKNOWN", rowFor(readiness().body, room.roomNumber).get("grade").asString())

        blockers.findByTaskIdAndResolvedAtIsNullOrderByBlockedAtAsc(task.getId()).forEach { blockers.delete(it) }
        blockers.save(TaskBlocker(task.getId(), BlockerType.LINEN_SHORTAGE, Instant.now(), estimatedResolutionAt = minutes(20)))

        val row = rowFor(readiness().body, room.roomNumber)
        assertEquals("ON_TRACK", row.get("grade").asString())
        assertEquals("BLOCKER", row.get("contributions").get(0).get("type").asString())
        assertConsistent(row)
    }

    @Test
    fun `other property's readiness is not found`() {
        val other = world.newHotel()
        assertProblem(api.get("/api/v1/properties/${hotel.property.getId()}/readiness", other.manager.getId()), 404, "NOT_FOUND")
    }

    private fun assertConsistent(row: JsonNode) {
        val calculatedAt = Instant.parse(row.get("calculatedAt").asString())
        val expected = Instant.parse(assertNotNull(row.get("expectedReadyAt")).asString())
        val sum = (0 until row.get("contributions").size()).sumOf { row.get("contributions").get(it).get("seconds").asLong() }
        assertEquals(Duration.between(calculatedAt, expected).seconds, sum)
    }
}
