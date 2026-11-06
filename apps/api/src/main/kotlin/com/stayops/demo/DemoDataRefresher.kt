package com.stayops.demo

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * 데모 데이터를 매일 새로 만든다. 데모 데이터의 시각(체크아웃·도착·근무)은 seed 시점 기준이라 하루가 지나면
 * 도착 시각이 모두 지나 화면이 낡아 보인다.
 *
 * - 기준 시각: 호텔 시간대 매일 [refreshAt](기본 04:00). 데모 호텔이 가장 최근 기준 시각 **이전에** 만들어졌으면 낡은 것으로 본다.
 * - 시작할 때 한 번, 그리고 주기적으로([DemoRefreshSchedule], `demo` profile만) 확인한다.
 * - `stayops.demo.force-refresh-on-startup=true`면 시작할 때 낡지 않았어도 새로 만든다(운영 테스트를 깨끗한 상태에서 시작할 때).
 * - 지우는 범위: 이름이 데모 호텔인 property와 그에 딸린 행뿐이다. 다른 호텔 데이터는 건드리지 않는다(테스트로 확인).
 * - 삭제와 다시 만들기는 한 트랜잭션이다. 도중에 실패하면 이전 데모 데이터가 그대로 남는다.
 * - 서버가 여러 대여도 한 번만 실행되도록 transaction 범위 advisory lock 안에서 다시 확인한다.
 */
@Component
@Profile("demo", "e2e")
class DemoDataRefresher(
    private val seeder: DemoSeeder,
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
    @Value("\${stayops.demo.refresh-at:04:00}") private val refreshAt: LocalTime,
    @Value("\${stayops.demo.force-refresh-on-startup:false}") private val forceOnStartup: Boolean,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

    override fun run(args: ApplicationArguments) {
        refreshIfStale(force = forceOnStartup)
    }

    /** 낡았으면(또는 [force]면) 지우고 다시 만든다. 새로 만들었으면 true. */
    fun refreshIfStale(force: Boolean = false): Boolean = transaction.execute {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", { _ -> }, LOCK_KEY)
        val demo = demoProperties()
        val current = demo.filter { it.name == DemoSeeder.HOTEL_NAME }
        val boundary = latestBoundary()
        if (!force && current.size == 1 && demo.size == 1 && !current.single().createdAt.isBefore(boundary)) return@execute false

        demo.forEach { delete(it.id) }
        seeder.seed()
        log.info("[demo] refreshed demo data (removed {} old demo hotel(s), boundary {})", demo.size, boundary)
        true
    } ?: false

    /** 가장 최근의 새로 만들기 기준 시각(호텔 시간대). */
    fun latestBoundary(): Instant {
        val now = ZonedDateTime.now(clock.withZone(HOTEL_ZONE))
        val today = now.toLocalDate().atTime(refreshAt).atZone(HOTEL_ZONE)
        return (if (now.isBefore(today)) today.minusDays(1) else today).toInstant()
    }

    private data class DemoProperty(val id: UUID, val name: String, val createdAt: Instant)

    private fun demoProperties(): List<DemoProperty> {
        val names = listOf(DemoSeeder.HOTEL_NAME) + DemoSeeder.LEGACY_HOTEL_NAMES
        return jdbc.query(
            "SELECT id, name, created_at FROM property WHERE name IN (${names.joinToString { "?" }})",
            { rs, _ -> DemoProperty(rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getTimestamp("created_at").toInstant()) },
            *names.toTypedArray(),
        )
    }

    /** 한 데모 호텔과 딸린 행을 FK 순서대로 지운다. 모든 문장이 property 범위로 묶여 있다. */
    private fun delete(propertyId: UUID) {
        val staffOf = "SELECT id FROM staff WHERE property_id = ?"
        val tasksOf = "SELECT id FROM housekeeping_task WHERE property_id = ?"
        listOf(
            "DELETE FROM idempotency_record WHERE actor_id IN ($staffOf)",
            "DELETE FROM operational_event WHERE property_id = ?",
            "DELETE FROM simulation_run WHERE property_id = ?",
            "DELETE FROM simulation_dataset WHERE property_id = ?",
            "DELETE FROM inspection_record WHERE task_id IN ($tasksOf)",
            "DELETE FROM task_blocker WHERE task_id IN ($tasksOf)",
            "DELETE FROM task_assignment WHERE task_id IN ($tasksOf)",
            "DELETE FROM housekeeping_task WHERE property_id = ?", // source_task_id 자기 참조는 같은 문장 안에서 함께 지워진다
            "DELETE FROM reservation WHERE property_id = ?",
            "DELETE FROM staff_shift WHERE staff_id IN ($staffOf)",
            "DELETE FROM staff WHERE property_id = ?",
            "DELETE FROM room WHERE property_id = ?",
            "DELETE FROM property WHERE id = ?",
        ).forEach { jdbc.update(it, propertyId) }
    }

    companion object {
        val HOTEL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private const val LOCK_KEY = 7_340_201_104L // "demo refresh" 전용 advisory lock 번호
    }
}

/** 주기적으로 낡았는지 확인한다(10분마다). 실제로 새로 만드는 것은 기준 시각을 지난 뒤 처음 한 번뿐이다. `e2e`에서는 끈다. */
@Configuration
@EnableScheduling
@Profile("demo")
class DemoRefreshSchedule(private val refresher: DemoDataRefresher) {
    @Scheduled(initialDelayString = "\${stayops.demo.refresh-check-interval:PT10M}", fixedDelayString = "\${stayops.demo.refresh-check-interval:PT10M}")
    fun check() {
        refresher.refreshIfStale()
    }
}
