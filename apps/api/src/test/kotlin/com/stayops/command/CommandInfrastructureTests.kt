package com.stayops.command

import com.stayops.TestcontainersConfiguration
import com.stayops.operations.infrastructure.OperationalEventRepository
import com.stayops.property.infrastructure.PropertyRepository
import com.stayops.room.infrastructure.RoomRepository
import com.stayops.shared.command.IdempotencyRecordRepository
import com.stayops.staff.infrastructure.StaffRepository
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P2.5 command 인프라 통합 테스트 (v4.1 02 §7, 04 §3·§4, 05 §4, ADR-003, ADR-005).
 * 실제 HTTP + PostgreSQL. 테스트마다 새 property를 만들어 데이터가 섞이지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("command-probe")
@Import(TestcontainersConfiguration::class)
class CommandInfrastructureTests(
    @Autowired private val json: JsonMapper,
    @Autowired private val properties: PropertyRepository,
    @Autowired private val rooms: RoomRepository,
    @Autowired private val staffs: StaffRepository,
    @Autowired private val events: OperationalEventRepository,
    @Autowired private val idempotencyRecords: IdempotencyRecordRepository,
    @Autowired private val meterRegistry: MeterRegistry,
    @LocalServerPort private val port: Int,
) {
    private lateinit var client: ProbeClient
    private lateinit var world: ProbeWorld

    @BeforeEach
    fun setUp() {
        client = ProbeClient(port, json)
        world = ProbeWorld(properties, rooms, staffs)
    }

    private fun roomEvents() = events.findByAggregateIdOrderByOccurredAtAscIdAsc(world.room.getId())

    private fun assertProblem(response: ProbeClient.Response, status: Int, code: String) {
        assertEquals(status, response.status, "body=${response.body}")
        assertEquals(code, response.code)
        assertTrue(response.contentType!!.startsWith("application/problem+json"), "content-type=${response.contentType}")
        assertNotNull(response.body.get("correlationId")?.asString(), "problem must carry correlationId")
        assertEquals("urn:stayops:problem:" + code.lowercase().replace('_', '-'), response.body.get("type").asString())
    }

    // ------------------------------------------------------- success & event

    @Test
    fun `command records an event in the same transaction with correlation, actor and key`() {
        val correlationId = UUID.randomUUID().toString()
        val response = client.post(world.room.getId(), mapOf("note" to "first"), world.manager.getId(), "key-1", correlationId)

        assertEquals(200, response.status, "body=${response.body}")
        assertEquals(correlationId, response.header("X-Correlation-Id"))
        assertEquals("false", response.header("Idempotency-Replayed"))

        val event = roomEvents().single()
        assertEquals(response.body.get("eventId").asString(), event.getId().toString())
        assertEquals(UUID.fromString(correlationId), event.correlationId)
        assertEquals(world.manager.getId(), event.actorId)
        assertEquals("key-1", event.idempotencyKey)
        assertEquals("PROBE_EXECUTED", event.eventType)
        assertEquals(world.room.version, event.aggregateVersion)
    }

    @Test
    fun `correlation id is generated when the header is missing or malformed`() {
        val missing = client.post(world.room.getId(), mapOf("note" to "a"), world.manager.getId())
        val malformed = client.post(world.room.getId(), mapOf("note" to "b"), world.manager.getId(), correlationId = "not-a-uuid")
        assertNotNull(UUID.fromString(missing.header("X-Correlation-Id")))
        assertNotEquals("not-a-uuid", malformed.header("X-Correlation-Id"))
        assertNotNull(UUID.fromString(malformed.header("X-Correlation-Id")))
    }

    // --------------------------------------------------------- idempotency

    @Test
    fun `same key and same request replays the first response without re-executing`() {
        val first = client.post(world.room.getId(), mapOf("note" to "once"), world.manager.getId(), "replay-key")
        val second = client.post(world.room.getId(), mapOf("note" to "once"), world.manager.getId(), "replay-key")

        assertEquals(200, second.status)
        assertEquals("true", second.header("Idempotency-Replayed"))
        assertEquals(first.body, second.body)
        assertEquals(1, roomEvents().size)
    }

    @Test
    fun `same key with a different request is rejected`() {
        client.post(world.room.getId(), mapOf("note" to "one"), world.manager.getId(), "conflict-key")
        val second = client.post(world.room.getId(), mapOf("note" to "two"), world.manager.getId(), "conflict-key")

        assertProblem(second, 409, "IDEMPOTENCY_CONFLICT")
        assertEquals(1, roomEvents().size)
    }

    @Test
    fun `same key reused on a different resource is rejected`() {
        val otherRoom = rooms.save(
            com.stayops.room.domain.Room(world.property.getId(), "102", "STANDARD", 1, com.stayops.room.domain.RoomOperationalStatus.READY),
        )
        client.post(world.room.getId(), mapOf("note" to "x"), world.manager.getId(), "resource-key")
        val second = client.post(otherRoom.getId(), mapOf("note" to "x"), world.manager.getId(), "resource-key")
        assertProblem(second, 409, "IDEMPOTENCY_CONFLICT")
    }

    @Test
    fun `missing or malformed idempotency key is a validation error`() {
        assertProblem(client.post(world.room.getId(), mapOf("note" to "x"), world.manager.getId(), idempotencyKey = null), 400, "VALIDATION_ERROR")
        assertProblem(client.post(world.room.getId(), mapOf("note" to "x"), world.manager.getId(), idempotencyKey = "has space"), 400, "VALIDATION_ERROR")
    }

    @Test
    fun `failed command is not stored and is re-evaluated on retry`() {
        val body = mapOf("note" to "x", "fail" to "DOMAIN")
        val first = client.post(world.room.getId(), body, world.manager.getId(), "fail-key")
        val retry = client.post(world.room.getId(), body, world.manager.getId(), "fail-key")

        assertProblem(first, 409, "INVALID_STATE")
        assertEquals("VACANT_DIRTY", first.body.get("currentStatus").asString())
        assertProblem(retry, 409, "INVALID_STATE")
        assertNull(idempotencyRecords.findByActorIdAndEndpointAndIdempotencyKey(world.manager.getId(), "POST /api/v1/test/rooms/{roomId}/probe", "fail-key"))
        assertTrue(roomEvents().isEmpty())
    }

    // ------------------------------------------------- actor & property scope

    @Test
    fun `missing, unknown or inactive actor is unauthenticated`() {
        assertProblem(client.post(world.room.getId(), mapOf("note" to "x"), staffId = null), 401, "UNAUTHENTICATED")
        assertProblem(client.post(world.room.getId(), mapOf("note" to "x"), UUID.randomUUID()), 401, "UNAUTHENTICATED")
        assertProblem(client.post(world.room.getId(), mapOf("note" to "x"), world.inactive.getId()), 401, "UNAUTHENTICATED")
    }

    @Test
    fun `room of another property is reported as not found`() {
        val other = ProbeWorld(properties, rooms, staffs)
        val response = client.post(other.room.getId(), mapOf("note" to "x"), world.manager.getId())
        assertProblem(response, 404, "NOT_FOUND")
    }

    // ----------------------------------------------------------- errors

    @Test
    fun `bean validation failure lists field errors`() {
        val response = client.post(world.room.getId(), mapOf("note" to " "), world.manager.getId())
        assertProblem(response, 400, "VALIDATION_ERROR")
        assertEquals("note", response.body.get("fieldErrors").get(0).get("field").asString())
    }

    @Test
    fun `state inconsistency is 409, counted, and hides diagnostics`() {
        val counter = meterRegistry.counter("stayops.state.inconsistent")
        val before = counter.count()
        val response = client.post(world.room.getId(), mapOf("note" to "x", "fail" to "INCONSISTENT"), world.manager.getId())

        assertProblem(response, 409, "STATE_INCONSISTENT")
        assertFalse(response.body.toString().contains("diagnostic-only"))
        assertEquals(before + 1, counter.count())
    }

    @Test
    fun `lock order violation is an internal error without leaking internals`() {
        val response = client.post(world.room.getId(), mapOf("note" to "x", "fail" to "LOCK_ORDER"), world.manager.getId())
        assertProblem(response, 500, "INTERNAL_ERROR")
        assertFalse(response.body.toString().contains("lock order"))
        assertFalse(response.body.toString().contains("Exception"))
    }

    // ------------------------------------------------------- concurrency

    @Test
    fun `concurrent requests with the same key execute once and both get the first response`() {
        val results = runConcurrently(2) {
            client.post(world.room.getId(), mapOf("note" to "race", "holdMillis" to 400), world.manager.getId(), "race-key")
        }
        results.forEach { assertEquals(200, it.status, "body=${it.body}") }
        assertEquals(results[0].body, results[1].body)
        assertEquals(setOf("false", "true"), results.map { it.header("Idempotency-Replayed") }.toSet())
        assertEquals(1, roomEvents().size)
    }

    @Test
    fun `commands on the same room are serialized by the room lock`() {
        val hold = 400L
        val started = System.nanoTime()
        val results = runConcurrently(2) { i ->
            client.post(world.room.getId(), mapOf("note" to "serial-$i", "holdMillis" to hold), world.manager.getId())
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        results.forEach { assertEquals(200, it.status, "body=${it.body}") }
        assertEquals(2, roomEvents().size)
        assertTrue(elapsedMillis >= 2 * hold - 50, "expected serialized execution (>= ${2 * hold}ms) but took ${elapsedMillis}ms")
    }

    private fun <T> runConcurrently(n: Int, action: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(n)
        val ready = CountDownLatch(n)
        val go = CountDownLatch(1)
        try {
            val futures = (0 until n).map { i ->
                pool.submit(Callable { ready.countDown(); go.await(); action(i) })
            }
            ready.await()
            go.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
