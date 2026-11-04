package com.stayops.readiness

import com.stayops.housekeeping.domain.BlockerType
import com.stayops.housekeeping.domain.DurationSource
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.readiness.domain.ContributionType
import com.stayops.readiness.domain.ReadinessCalculator
import com.stayops.readiness.domain.ReadinessGrade
import com.stayops.readiness.domain.ReadinessResult
import com.stayops.readiness.domain.ReadinessSettings
import com.stayops.readiness.domain.ReadinessSnapshot
import com.stayops.readiness.domain.ReadinessSnapshot.BlockerSnapshot
import com.stayops.readiness.domain.ReadinessSnapshot.TaskSnapshot
import com.stayops.room.domain.RoomAvailabilityStatus
import com.stayops.room.domain.RoomOperationalStatus
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** readiness 계산기 단위 테스트 (docs/v4.1/05 §5). DB 없이 스냅샷과 기준 시각만 쓴다. */
class ReadinessCalculatorTests {

    private val now: Instant = Instant.parse("2026-11-05T03:00:00Z")
    private val settings = ReadinessSettings(Duration.ofMinutes(30), "readiness-v1")
    private val defaults = mapOf(TaskType.CHECKOUT_CLEANING to 45, TaskType.RE_CLEANING to 30, TaskType.INSPECTION to 10, TaskType.STAYOVER_CLEANING to 25)

    private fun min(n: Long): Duration = Duration.ofMinutes(n)

    private fun snapshot(
        status: RoomOperationalStatus,
        checkInIn: Duration = min(300),
        task: TaskSnapshot? = null,
        availability: RoomAvailabilityStatus = RoomAvailabilityStatus.AVAILABLE,
        currentStayCheckOutAt: Instant? = null,
        awaitingInspection: Boolean = false,
        buffer: Int = 30,
    ) = ReadinessSnapshot(
        roomId = UUID.randomUUID(), roomNumber = "101", operationalStatus = status, availabilityStatus = availability,
        workflowStage = status.name, reservationId = UUID.randomUUID(), scheduledCheckInAt = now.plus(checkInIn),
        operationalBufferMinutes = buffer, currentStayCheckOutAt = currentStayCheckOutAt, activeTask = task,
        awaitingInspection = awaitingInspection, defaultDurationMinutes = defaults,
    )

    private fun task(
        type: TaskType = TaskType.CHECKOUT_CLEANING,
        status: TaskStatus = TaskStatus.ASSIGNED,
        durationMinutes: Int = 40,
        scheduledAt: Instant = now,
        startedAgo: Duration? = null,
        hasAssignee: Boolean = true,
        blockers: List<BlockerSnapshot> = emptyList(),
    ) = TaskSnapshot(
        taskId = UUID.randomUUID(), type = type, status = status, scheduledAt = scheduledAt,
        estimatedDurationMinutes = durationMinutes, durationSource = DurationSource.USER_INPUT,
        actualStartedAt = startedAgo?.let { now.minus(it) }, hasAssignee = hasAssignee, blockers = blockers,
    )

    private fun blocker(blockedAgo: Duration, resolvesIn: Duration?, resolvedAgo: Duration? = null, type: BlockerType = BlockerType.LINEN_SHORTAGE) =
        BlockerSnapshot(UUID.randomUUID(), type, now.minus(blockedAgo), resolvesIn?.let { now.plus(it) }, resolvedAgo?.let { now.minus(it) })

    private fun calc(s: ReadinessSnapshot, at: Instant = now) = ReadinessCalculator.calculate(s, settings, at)

    /** 05 §5: breakdown 합계와 expectedReadyAt의 일관성. 모든 계산 가능한 결과에서 확인한다. */
    private fun assertConsistent(r: ReadinessResult) {
        val expected = assertNotNull(r.expectedReadyAt)
        assertEquals(Duration.between(r.calculatedAt, expected).seconds, r.contributions.sumOf { it.seconds }, "contributions must sum to expectedReadyAt - calculatedAt")
    }

    // ------------------------------------------------------------- normal

    @Nested
    inner class Normal {
        @Test
        fun `dirty room with a pending cleaning adds cleaning then a default inspection`() {
            val r = calc(snapshot(RoomOperationalStatus.VACANT_DIRTY, task = task(durationMinutes = 40)))
            assertEquals(now.plus(min(50)), r.expectedReadyAt)
            assertEquals(listOf(ContributionType.CLEANING, ContributionType.INSPECTION), r.contributions.map { it.type })
            assertEquals(DurationSource.CONFIG_DEFAULT, r.contributions[1].durationSource)
            assertEquals(ReadinessGrade.ON_TRACK, r.grade) // deadline = now+270m
            assertConsistent(r)
            assertTrue(r.assumptions.any { it.contains("검사 통과") })
        }

        @Test
        fun `future scheduled task waits until its scheduled time`() {
            val r = calc(snapshot(RoomOperationalStatus.VACANT_DIRTY, task = task(scheduledAt = now.plus(min(60)))))
            assertEquals(listOf(ContributionType.WAIT_FOR_SCHEDULE, ContributionType.CLEANING, ContributionType.INSPECTION), r.contributions.map { it.type })
            assertEquals(now.plus(min(110)), r.expectedReadyAt)
            assertConsistent(r)
        }

        @Test
        fun `awaiting inspection only needs the inspection`() {
            val r = calc(snapshot(RoomOperationalStatus.VACANT_DIRTY, task = task(TaskType.INSPECTION, durationMinutes = 15), awaitingInspection = true))
            assertEquals(now.plus(min(15)), r.expectedReadyAt)
            assertConsistent(r)
        }

        @Test
        fun `occupied room waits for the scheduled check-out and uses default durations`() {
            val r = calc(snapshot(RoomOperationalStatus.OCCUPIED, currentStayCheckOutAt = now.plus(min(120))))
            assertEquals(listOf(ContributionType.WAIT_FOR_CHECKOUT, ContributionType.CLEANING, ContributionType.INSPECTION), r.contributions.map { it.type })
            assertEquals(now.plus(min(120 + 45 + 10)), r.expectedReadyAt)
            assertTrue(r.assumptions.any { it.contains("예정 체크아웃") })
            assertConsistent(r)
        }

        @Test
        fun `unassigned task is calculated but flagged (staffing not modeled)`() {
            val r = calc(snapshot(RoomOperationalStatus.VACANT_DIRTY, task = task(status = TaskStatus.PENDING, hasAssignee = false)))
            assertNotNull(r.expectedReadyAt)
            assertTrue("UNASSIGNED" in r.flags)
        }

        @Test
        fun `result carries snapshot, version and calculation time, and is deterministic`() {
            val s = snapshot(RoomOperationalStatus.VACANT_DIRTY, task = task())
            val a = calc(s)
            val b = calc(s)
            assertEquals(a, b)
            assertEquals(s, a.inputSnapshot)
            assertEquals("readiness-v1", a.calculationVersion)
            assertEquals(now, a.calculatedAt)
        }
    }

    // ------------------------------------------------------- READY & OOS

    @Nested
    inner class Ready {
        @Test
        fun `READY room is READY without a time estimate`() {
            val r = calc(snapshot(RoomOperationalStatus.READY))
            assertEquals(ReadinessGrade.READY, r.grade)
            assertNull(r.expectedReadyAt)
            assertTrue(r.flags.isEmpty())
        }

        @Test
        fun `READY but OUT_OF_SERVICE shows both ready and not check-in-able`() {
            val r = calc(snapshot(RoomOperationalStatus.READY, availability = RoomAvailabilityStatus.OUT_OF_SERVICE))
            assertEquals(ReadinessGrade.READY, r.grade)
            assertEquals(RoomAvailabilityStatus.OUT_OF_SERVICE, r.availabilityStatus)
            assertTrue("CANNOT_CHECK_IN_OUT_OF_SERVICE" in r.flags)
        }
    }

    // ------------------------------------------------------------ UNKNOWN

    @Nested
    inner class Unknown {
        @Test
        fun `dirty room without a cleaning task is UNKNOWN`() {
            val r = calc(snapshot(RoomOperationalStatus.VACANT_DIRTY, task = null))
            assertEquals(ReadinessGrade.UNKNOWN, r.grade)
            assertNull(r.expectedReadyAt)
            assertNull(r.riskMinutes)
            assertTrue(r.missingInputs.single().contains("cleaning task missing"))
        }

        @Test
        fun `blocker without an estimated resolution is never replaced by an arbitrary time`() {
            val r = calc(snapshot(RoomOperationalStatus.VACANT_DIRTY, task = task(status = TaskStatus.BLOCKED, blockers = listOf(blocker(min(5), resolvesIn = null)))))
            assertEquals(ReadinessGrade.UNKNOWN, r.grade)
            assertTrue(r.missingInputs.single().startsWith("blocker.estimatedResolutionAt"))
            assertTrue(r.contributions.none { it.type == ContributionType.BLOCKER })
        }

        @Test
        fun `blocker whose estimate already passed is UNKNOWN`() {
            val r = calc(snapshot(RoomOperationalStatus.VACANT_DIRTY, task = task(status = TaskStatus.BLOCKED, blockers = listOf(blocker(min(60), resolvesIn = min(-10))))))
            assertEquals(ReadinessGrade.UNKNOWN, r.grade)
        }

        @Test
        fun `occupied room whose scheduled check-out has passed is UNKNOWN`() {
            val r = calc(snapshot(RoomOperationalStatus.OCCUPIED, currentStayCheckOutAt = now.minus(min(30))))
            assertEquals(ReadinessGrade.UNKNOWN, r.grade)
            assertTrue(r.missingInputs.single().contains("actualCheckOutAt"))
        }

        @Test
        fun `started work without an assignee is UNKNOWN and flagged`() {
            val r = calc(snapshot(RoomOperationalStatus.CLEANING, task = task(status = TaskStatus.PENDING, startedAgo = min(10), hasAssignee = false)))
            assertEquals(ReadinessGrade.UNKNOWN, r.grade)
            assertTrue("WORK_INTERRUPTED_NO_ASSIGNEE" in r.flags)
        }
    }

    // ------------------------------------------------------------ blockers

    @Nested
    inner class Blockers {
        @Test
        fun `parallel blockers are not summed - only the latest resolution counts`() {
            val early = blocker(min(5), resolvesIn = min(30))
            val late = blocker(min(3), resolvesIn = min(50), type = BlockerType.MAINTENANCE)
            val r = calc(snapshot(RoomOperationalStatus.VACANT_DIRTY, task = task(status = TaskStatus.BLOCKED, blockers = listOf(early, late))))

            val blockerContribution = r.contributions.single { it.type == ContributionType.BLOCKER }
            assertEquals(min(50).seconds, blockerContribution.seconds)
            assertEquals(late.blockerId, blockerContribution.sourceId)
            assertEquals(listOf(early.blockerId), r.overlappedBlockers)
            assertEquals(now.plus(min(50 + 40 + 10)), r.expectedReadyAt)
            assertConsistent(r)
        }

        @Test
        fun `blocked in-progress work resumes after the blocker and keeps the time already worked`() {
            val t = task(status = TaskStatus.BLOCKED, startedAgo = min(30), durationMinutes = 40, blockers = listOf(blocker(min(10), resolvesIn = min(15))))
            val r = calc(snapshot(RoomOperationalStatus.CLEANING, task = t))
            // 실작업 20분(30 − 차단 10) → 남은 청소 20분, 차단 해제까지 15분 대기, 검사 10분
            assertEquals(listOf(min(15).seconds, min(20).seconds, min(10).seconds), r.contributions.map { it.seconds })
            assertEquals(now.plus(min(45)), r.expectedReadyAt)
            assertConsistent(r)
        }
    }

    // ---------------------------------------------------------- in progress

    @Nested
    inner class InProgress {
        @Test
        fun `elapsed work is not counted twice`() {
            val r = calc(snapshot(RoomOperationalStatus.CLEANING, task = task(status = TaskStatus.IN_PROGRESS, startedAgo = min(15), durationMinutes = 40)))
            assertEquals(listOf(min(25).seconds, min(10).seconds), r.contributions.map { it.seconds })
            assertConsistent(r)
        }

        @Test
        fun `resolved blocker periods are excluded from worked time, overlapping periods counted once`() {
            val blockers = listOf(
                blocker(min(25), resolvesIn = null, resolvedAgo = min(15)), // 10분 차단
                blocker(min(20), resolvesIn = null, resolvedAgo = min(10)), // 겹침 → 합집합 15분
            )
            val r = calc(snapshot(RoomOperationalStatus.CLEANING, task = task(status = TaskStatus.IN_PROGRESS, startedAgo = min(30), durationMinutes = 40, blockers = blockers)))
            // 실작업 = 30 − 15 = 15 → 남은 25
            assertEquals(min(25).seconds, r.contributions.first().seconds)
            assertConsistent(r)
        }

        @Test
        fun `overrun work contributes zero remaining and is flagged`() {
            val r = calc(snapshot(RoomOperationalStatus.CLEANING, task = task(status = TaskStatus.IN_PROGRESS, startedAgo = min(60), durationMinutes = 40)))
            assertEquals(0, r.contributions.first().seconds)
            assertTrue("OVERRUN" in r.flags)
            assertConsistent(r)
        }

        @Test
        fun `inspection in progress needs only the remaining inspection`() {
            val r = calc(snapshot(RoomOperationalStatus.INSPECTING, task = task(TaskType.INSPECTION, TaskStatus.IN_PROGRESS, durationMinutes = 10, startedAgo = min(4))))
            assertEquals(listOf(ContributionType.INSPECTION), r.contributions.map { it.type })
            assertEquals(now.plus(min(6)), r.expectedReadyAt)
        }
    }

    // ------------------------------------------------- grade boundaries

    @Nested
    inner class Boundaries {
        /** 남은 시간 50분(청소 40 + 검사 10), buffer 30 → 마감 = check-in − 30분. */
        private fun gradeWhenCheckInIn(checkInIn: Duration) =
            calc(snapshot(RoomOperationalStatus.VACANT_DIRTY, checkInIn = checkInIn, task = task(durationMinutes = 40)))

        @Test
        fun `expected 1 second after the deadline is AT_RISK`() {
            val r = gradeWhenCheckInIn(min(80).minusSeconds(1))
            assertEquals(ReadinessGrade.AT_RISK, r.grade)
            assertEquals(1, r.riskMinutes)
        }

        @Test
        fun `expected exactly at the deadline is WARNING`() {
            val r = gradeWhenCheckInIn(min(80))
            assertEquals(ReadinessGrade.WARNING, r.grade)
            assertEquals(0, r.riskMinutes)
        }

        @Test
        fun `just inside the warning margin is WARNING, exactly at the margin is ON_TRACK`() {
            assertEquals(ReadinessGrade.WARNING, gradeWhenCheckInIn(min(110).minusSeconds(1)).grade)
            assertEquals(ReadinessGrade.ON_TRACK, gradeWhenCheckInIn(min(110)).grade)
        }

        @Test
        fun `late arrival with a passed deadline and a dirty room is AT_RISK`() {
            val r = gradeWhenCheckInIn(min(-60))
            assertEquals(ReadinessGrade.AT_RISK, r.grade)
            assertEquals(140, r.riskMinutes)
        }

        @Test
        fun `deadline is computed on instants, so a DST change does not shift it`() {
            val ny = ZoneId.of("America/New_York")
            // 2026-03-08 02:00 EST → 03:00 EDT. 체크인 04:00 EDT, buffer 180분 → 마감은 정확히 180분 전(00:00 EST)
            val checkIn = LocalDateTime.of(2026, 3, 8, 4, 0).atZone(ny).toInstant()
            val at = checkIn.minus(Duration.ofHours(6))
            val s = snapshot(RoomOperationalStatus.READY, buffer = 180).copy(scheduledCheckInAt = checkIn)
            val r = ReadinessCalculator.calculate(s, settings, at)
            assertEquals(Duration.ofMinutes(180), Duration.between(r.readyDeadline, checkIn))
            assertEquals(LocalDateTime.of(2026, 3, 8, 0, 0), r.readyDeadline.atZone(ny).toLocalDateTime())
        }
    }
}
