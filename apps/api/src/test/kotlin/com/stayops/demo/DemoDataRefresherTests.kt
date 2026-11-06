package com.stayops.demo

import com.stayops.TestcontainersConfiguration
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.support.StayWorld
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.sql.Timestamp
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 데모 데이터 매일 새로 만들기. 낡은 데모 호텔만 지우고 다시 만들며, 다른 호텔 데이터는 건드리지 않는다.
 * `demo` profile로 띄워 시작 시 seed가 실제로 돈 상태에서 확인한다.
 */
@SpringBootTest(properties = ["stayops.demo.refresh-check-interval=PT24H"]) // 테스트 중 주기 실행이 끼어들지 않게
@ActiveProfiles("demo")
@Import(TestcontainersConfiguration::class)
class DemoDataRefresherTests(
    @Autowired private val refresher: DemoDataRefresher,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val world: StayWorld,
) {
    private fun demoIds(): List<UUID> =
        jdbc.queryForList("SELECT id FROM property WHERE name = ? ORDER BY created_at", UUID::class.java, DemoSeeder.HOTEL_NAME).filterNotNull()

    private fun ageDemo(id: UUID) =
        jdbc.update("UPDATE property SET created_at = ? WHERE id = ?", Timestamp.from(refresher.latestBoundary().minus(Duration.ofMinutes(1))), id)

    private fun rowsOf(propertyId: UUID): Map<String, Int> = mapOf(
        "room" to "SELECT count(*) FROM room WHERE property_id = ?",
        "staff" to "SELECT count(*) FROM staff WHERE property_id = ?",
        "reservation" to "SELECT count(*) FROM reservation WHERE property_id = ?",
        "task" to "SELECT count(*) FROM housekeeping_task WHERE property_id = ?",
        "event" to "SELECT count(*) FROM operational_event WHERE property_id = ?",
    ).mapValues { (_, sql) -> jdbc.queryForObject(sql, Int::class.java, propertyId)!! }

    @Test
    fun `fresh demo data is kept, stale demo data is replaced without touching other hotels`() {
        assertFalse(refresher.refreshIfStale(), "data seeded at startup is fresh")
        val old = demoIds().single()
        assertTrue(rowsOf(old).values.all { it > 0 }, "seed created rooms, staff, reservations, tasks and events: ${rowsOf(old)}")

        val other = world.newHotel()
        other.turnover(status = TaskStatus.ASSIGNED, assignee = other.housekeeper)
        val otherBefore = rowsOf(other.property.getId())

        ageDemo(old)
        assertTrue(refresher.refreshIfStale())

        val new = demoIds().single()
        assertNotEquals(old, new)
        assertEquals(0, rowsOf(old).values.sum(), "every row of the old demo hotel is gone")
        assertTrue(rowsOf(new).values.all { it > 0 })
        assertEquals(otherBefore, rowsOf(other.property.getId()), "another hotel is untouched")
        assertFalse(refresher.refreshIfStale(), "the next check right after is a no-op")

        assertTrue(refresher.refreshIfStale(force = true), "force recreates even fresh data")
        assertNotEquals(new, demoIds().single())
    }

    @Test
    fun `a demo hotel under the previous name is cleaned up on refresh`() {
        val legacy = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO property (id, name, timezone, default_check_in_time, default_check_out_time, operational_buffer_minutes, created_at, updated_at, version) " +
                "VALUES (?, ?, 'Asia/Seoul', '15:00', '11:00', 30, now(), now(), 0)",
            legacy, DemoSeeder.LEGACY_HOTEL_NAMES.first(),
        )
        assertTrue(refresher.refreshIfStale())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM property WHERE id = ?", Int::class.java, legacy))
        assertEquals(1, demoIds().size)
    }
}
