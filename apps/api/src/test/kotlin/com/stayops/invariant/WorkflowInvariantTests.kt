package com.stayops.invariant

import com.stayops.TestcontainersConfiguration
import com.stayops.housekeeping.infrastructure.HousekeepingTaskRepository
import com.stayops.housekeeping.infrastructure.TaskAssignmentRepository
import com.stayops.housekeeping.infrastructure.TaskBlockerRepository
import com.stayops.reservation.infrastructure.ReservationRepository
import com.stayops.room.infrastructure.RoomRepository
import com.stayops.support.ApiClient
import com.stayops.support.StayWorld
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * P6 동시성·불변식 강화 테스트 (docs/v4.1/05 §4·§6, ADR-003).
 *
 * 무작위 command sequence를 실제 HTTP로 실행하고, 매 단계(또는 동시 실행 묶음) 뒤에 DB 불변식을 검사한다.
 * - 응답은 2xx 또는 4xx(400/403/404/409)여야 하며, 5xx·401·STATE_INCONSISTENT는 실패다.
 * - 실패하면 seed와 command 기록을 `build/invariant-failures/`에 남긴다.
 *
 * 재현: `STAYOPS_INVARIANT_SEEDS=42,77 ./gradlew test --tests '*WorkflowInvariantTests*'`
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class WorkflowInvariantTests(
    @Autowired private val json: JsonMapper,
    @Autowired private val world: StayWorld,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val tasks: HousekeepingTaskRepository,
    @Autowired private val assignments: TaskAssignmentRepository,
    @Autowired private val blockers: TaskBlockerRepository,
    @Autowired private val reservations: ReservationRepository,
    @Autowired private val rooms: RoomRepository,
    @LocalServerPort private val port: Int,
) {
    private val checker = InvariantChecker(jdbc)

    private fun seeds(defaults: List<Long>): List<Long> =
        System.getenv("STAYOPS_INVARIANT_SEEDS")?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.takeIf { it.isNotEmpty() } ?: defaults

    private class Run(val seed: Long, val mode: String) {
        val log = mutableListOf<String>()
        val statusCounts = sortedMapOf<String, Int>()
        val successByKind = sortedMapOf<String, Int>()
    }

    private fun newRun(seed: Long, mode: String): Triple<Run, StayWorld.Hotel, RandomCommands> {
        val hotel = world.newHotel()
        val generator = RandomCommands(
            seed, hotel, ApiClient(port, json), RandomCommands.initialRooms(hotel),
            tasks, assignments, blockers, reservations, rooms,
        )
        return Triple(Run(seed, mode), hotel, generator)
    }

    /** 허용되는 응답인지 검사하고 기록한다. */
    private fun observe(run: Run, step: String, description: String, response: ApiClient.Response) {
        val code = response.code ?: response.status.toString()
        run.log += "$step ${response.status} $code  $description"
        run.statusCounts.merge(code, 1, Int::plus)
        if (response.status in 200..299) run.successByKind.merge(description.substringBefore(' '), 1, Int::plus)
        val acceptable = response.status in 200..299 || (response.status in setOf(400, 403, 404, 409) && code != "STATE_INCONSISTENT")
        if (!acceptable) failRun(run, "unacceptable response at $step: ${response.status} ${response.body}")
    }

    private fun assertInvariants(run: Run, hotel: StayWorld.Hotel, step: String) {
        val violations = checker.violations(hotel.property.getId())
        if (violations.isNotEmpty()) failRun(run, "invariant violated after $step:\n" + violations.joinToString("\n"))
    }

    private fun failRun(run: Run, message: String): Nothing {
        val dir = File("build/invariant-failures").apply { mkdirs() }
        val file = File(dir, "${run.mode}-seed-${run.seed}.log")
        file.writeText("seed=${run.seed} mode=${run.mode}\n$message\n\n--- command log ---\n" + run.log.joinToString("\n"))
        fail("$message\nseed=${run.seed} (reproduce with STAYOPS_INVARIANT_SEEDS=${run.seed}); log: ${file.absolutePath}")
    }

    // ----------------------------------------------------------- sequential

    @TestFactory
    fun `random command sequences keep the invariants after every step`(): List<DynamicTest> =
        seeds((1L..12L).toList()).map { seed ->
            DynamicTest.dynamicTest("sequential seed=$seed") {
                val (run, hotel, generator) = newRun(seed, "sequential")
                repeat(STEPS) { i ->
                    generator.topUpReservations()
                    val command = generator.next()
                    observe(run, "#$i", command.description, command.run())
                    assertInvariants(run, hotel, "#$i ${command.description}")
                }
                println("[invariant] sequential seed=$seed responses=${run.statusCounts} succeeded=${run.successByKind}")
            }
        }

    // ----------------------------------------------------------- concurrent

    @TestFactory
    fun `concurrent command bursts keep the invariants`(): List<DynamicTest> =
        seeds((101L..106L).toList()).map { seed ->
            DynamicTest.dynamicTest("concurrent seed=$seed") {
                val (run, hotel, generator) = newRun(seed, "concurrent")
                repeat(BURSTS) { b ->
                    generator.topUpReservations()
                    val commands = List(BURST_SIZE) { generator.next() }
                    val responses = runConcurrently(commands.size) { commands[it].run() }
                    commands.zip(responses).forEachIndexed { i, (c, r) -> observe(run, "burst#$b.$i", c.description, r) }
                    assertInvariants(run, hotel, "burst#$b")
                }
                println("[invariant] concurrent seed=$seed responses=${run.statusCounts} succeeded=${run.successByKind}")
            }
        }

    // ------------------------------------------------------------- deadlock

    /**
     * 같은 객실들에 많은 command를 동시에 보내도 PostgreSQL deadlock이 0건이어야 한다 (ADR-003 잠금 순서).
     * deadlock은 409로 변환되어 응답만으로는 구분되지 않으므로 pg_stat_database 카운터로 확인한다.
     */
    @Test
    fun `heavy concurrent load on the same rooms causes no deadlocks`() {
        val (run, hotel, generator) = newRun(9001, "deadlock")
        val before = deadlocks()
        repeat(DEADLOCK_ROUNDS) { round ->
            generator.topUpReservations()
            val commands = List(DEADLOCK_CONCURRENCY) { generator.next() }
            val responses = runConcurrently(commands.size) { commands[it].run() }
            commands.zip(responses).forEachIndexed { i, (c, r) -> observe(run, "round#$round.$i", c.description, r) }
            assertInvariants(run, hotel, "round#$round")
        }
        Thread.sleep(1500) // 누적 통계는 backend가 비동기로 반영한다
        jdbc.execute("select pg_stat_clear_snapshot()")
        assertEquals(before, deadlocks(), "PostgreSQL reported deadlocks during the run; responses=${run.statusCounts}")
        println("[invariant] deadlock run responses=${run.statusCounts} succeeded=${run.successByKind}")
    }

    private fun deadlocks(): Long =
        jdbc.queryForObject("select deadlocks from pg_stat_database where datname = current_database()", Long::class.java) ?: 0

    private fun <T> runConcurrently(n: Int, action: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(n)
        val ready = CountDownLatch(n)
        val go = CountDownLatch(1)
        try {
            val futures = (0 until n).map { i -> pool.submit(Callable { ready.countDown(); go.await(); action(i) }) }
            ready.await()
            go.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        private const val STEPS = 60
        private const val BURSTS = 20
        private const val BURST_SIZE = 3
        private const val DEADLOCK_ROUNDS = 25
        private const val DEADLOCK_CONCURRENCY = 8
    }
}
