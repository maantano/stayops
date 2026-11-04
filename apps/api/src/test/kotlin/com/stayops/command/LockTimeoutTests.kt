package com.stayops.command

import com.stayops.TestcontainersConfiguration
import com.stayops.operations.infrastructure.OperationalEventRepository
import com.stayops.property.infrastructure.PropertyRepository
import com.stayops.room.infrastructure.RoomRepository
import com.stayops.staff.infrastructure.StaffRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-003: 객실 잠금 대기가 lock_timeout을 넘으면 409 VERSION_CONFLICT, 부분 반영 없음. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["stayops.db.lock-timeout=300ms"],
)
@ActiveProfiles("command-probe")
@Import(TestcontainersConfiguration::class)
class LockTimeoutTests(
    @Autowired private val json: JsonMapper,
    @Autowired private val properties: PropertyRepository,
    @Autowired private val rooms: RoomRepository,
    @Autowired private val staffs: StaffRepository,
    @Autowired private val events: OperationalEventRepository,
    @LocalServerPort private val port: Int,
) {
    @Test
    fun `waiting longer than lock_timeout for the room lock returns VERSION_CONFLICT`() {
        val client = ProbeClient(port, json)
        val world = ProbeWorld(properties, rooms, staffs)

        ProbeSignals.lockAcquired = CountDownLatch(1)
        val holder = CompletableFuture.supplyAsync {
            client.post(world.room.getId(), mapOf("note" to "holder", "holdMillis" to 1500), world.manager.getId())
        }
        assertTrue(ProbeSignals.lockAcquired.await(10, TimeUnit.SECONDS), "holder never acquired the room lock")
        val waiter = client.post(world.room.getId(), mapOf("note" to "waiter"), world.manager.getId())

        assertEquals(409, waiter.status, "body=${waiter.body}")
        assertEquals("VERSION_CONFLICT", waiter.code)
        assertEquals(200, holder.get(10, TimeUnit.SECONDS).status)
        assertEquals(listOf("holder"), events.findByAggregateIdOrderByOccurredAtAscIdAsc(world.room.getId()).map {
            json.readTree(it.payloadJson).get("note").asString()
        })
    }
}
