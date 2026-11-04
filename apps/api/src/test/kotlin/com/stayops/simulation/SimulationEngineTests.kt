package com.stayops.simulation

import com.stayops.simulation.engine.SimDataset
import com.stayops.simulation.engine.SimDataset.Realization
import com.stayops.simulation.engine.SimDataset.SimReservation
import com.stayops.simulation.engine.SimDataset.SimShift
import com.stayops.simulation.engine.SimDataset.SimStaff
import com.stayops.simulation.engine.SimDataset.StayoverRequest
import com.stayops.simulation.engine.SimPolicy
import com.stayops.simulation.engine.SimulationEngine
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 시뮬레이션 엔진 단위 테스트. 손으로 계산할 수 있는 작은 데이터셋으로 각 규칙을 확인한다.
 * 기본: 기간 2026-11-09부터 2일, Asia/Seoul, buffer 30분. 하우스키퍼 08–16시(휴게 30분은 11:45–12:15), 검사자 09–17시.
 */
class SimulationEngineTests {

    private val zone = ZoneId.of("Asia/Seoul")
    private val day0 = LocalDate.of(2026, 11, 9)

    private fun at(day: Int, time: String): Instant = day0.plusDays(day.toLong()).atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    private fun realization(
        cleaning: Int = 40,
        inspection: Int = 10,
        fails: Boolean = false,
        linen: Int = 0,
        maintenance: Int = 0,
        stayovers: List<StayoverRequest> = emptyList(),
    ) = Realization(cleaning, inspection, fails, if (fails) 20 else null, if (fails) 8 else null, linen, maintenance, stayovers)

    private fun stay(room: String, checkIn: Instant, checkOut: Instant, r: Realization = realization()) =
        SimReservation("res-$room-${checkIn.epochSecond}", "room-$room", room, checkIn, checkOut, r)

    /** 객실 [room]의 turnover 하나: [checkoutAt]에 퇴실, 다음 손님은 [nextCheckIn]에 도착(체크아웃은 기간 밖). */
    private fun turnover(room: String, checkoutAt: Instant, nextCheckIn: Instant, r: Realization = realization()) = listOf(
        stay(room, checkoutAt.minusSeconds(86_400), checkoutAt, r),
        stay(room, nextCheckIn, nextCheckIn.plusSeconds(10 * 86_400)),
    )

    private fun dataset(
        reservations: List<SimReservation>,
        housekeeperShift: Pair<String, String> = "08:00" to "16:00",
        housekeepers: Int = 1,
        days: Int = 2,
    ): SimDataset {
        val staff = (1..housekeepers).map { SimStaff("hk-$it", "HK-0$it", "HOUSEKEEPER") } + SimStaff("in-1", "IN-01", "INSPECTOR")
        val shifts = (0 until days).flatMap { d ->
            staff.map { s ->
                if (s.role == "HOUSEKEEPER") SimShift(s.staffId, at(d, housekeeperShift.first), at(d, housekeeperShift.second), 30)
                else SimShift(s.staffId, at(d, "09:00"), at(d, "17:00"), 30)
            }
        }
        val rooms = reservations.map { it.roomNumber }.distinct().map { SimDataset.SimRoom("room-$it", it, "STANDARD") }
        return SimDataset(
            metadata = SimDataset.Metadata(true, "gen-v1", 1, "hash", SimDataset.Horizon(day0, days, "Asia/Seoul")),
            property = SimDataset.SimProperty("test", "Asia/Seoul", 30),
            rooms = rooms, staff = staff, shifts = shifts, reservations = reservations,
        )
    }

    private fun run(d: SimDataset, policy: SimPolicy = SimPolicy.BASELINE_FIFO) = SimulationEngine.run(d, policy)

    @Test
    fun `turnover cleaned and inspected before the deadline is on time`() {
        // 11:00 퇴실 → 청소 11:00–11:40 → 검사 11:40–11:50, 마감 14:30
        val m = run(dataset(turnover("101", at(0, "11:00"), at(0, "15:00"))))
        assertEquals(1, m.evaluatedArrivals)
        assertEquals(1, m.onTime)
        assertEquals(1.0, m.onTimeReadinessRate)
        assertNull(m.meanLatenessMinutes)
        // 하우스키퍼: 근무 가능 2일 × (480 − 30) = 900분 중 40분
        assertEquals(900, m.workload.getValue("HOUSEKEEPER").availableMinutes)
        assertEquals(40, m.workload.getValue("HOUSEKEEPER").busyMinutes)
        assertEquals(0.0444, m.workload.getValue("HOUSEKEEPER").utilization)
    }

    @Test
    fun `work waits for the shift to start and lateness is measured against the deadline`() {
        // 06:00 퇴실, 07:50 도착(마감 07:20) → 근무 08:00 시작 → 08:40 청소 끝, 검사자는 09:00부터 → 09:10 준비 → 110분 지연
        val m = run(dataset(turnover("101", at(0, "06:00"), at(0, "07:50"))))
        assertEquals(1, m.late)
        assertEquals(110.0, m.meanLatenessMinutes)
        assertEquals(110, m.maxLatenessMinutes)
    }

    @Test
    fun `deadline-first serves the urgent room first where FIFO does not`() {
        // 하우스키퍼 1명, 두 객실 모두 11:00 퇴실. 101은 내일 도착, 102는 12:30 도착(마감 12:00)
        val d = dataset(turnover("101", at(0, "11:00"), at(1, "15:00")) + turnover("102", at(0, "11:00"), at(0, "12:30")))
        val fifo = run(d, SimPolicy.BASELINE_FIFO)
        val deadlineFirst = run(d, SimPolicy.DEADLINE_FIRST)
        assertEquals(0.5, fifo.onTimeReadinessRate) // 102: 11:40–12:20 청소, 12:30 준비 → 30분 지연
        assertEquals(30.0, fifo.meanLatenessMinutes)
        assertEquals(1.0, deadlineFirst.onTimeReadinessRate) // 102 먼저: 11:50 준비
    }

    @Test
    fun `failed inspection adds a re-cleaning and a re-inspection`() {
        val m = run(dataset(turnover("101", at(0, "11:00"), at(0, "15:00"), realization(fails = true))))
        assertEquals(1, m.inspectionFailures)
        assertEquals(1, m.jobs.getValue("RE_CLEANING").completed)
        assertEquals(1, m.jobs.getValue("RE_INSPECTION").completed)
        assertEquals(1, m.onTime) // 11:00–11:40, 11:40–11:50 실패, 재청소(휴게 11:45–12:15 이후) 12:15–12:35, 재검사 12:35–12:43
    }

    @Test
    fun `linen and maintenance waits delay the start of cleaning`() {
        // 10:00 퇴실 + 린넨 20 + 시설 10 → 10:30 청소 시작 → 11:10 → 검사 11:20, 마감(11:30 도착 − 30) 11:00 → 20분 지연
        val m = run(dataset(turnover("101", at(0, "10:00"), at(0, "11:30"), realization(linen = 20, maintenance = 10))))
        assertEquals(20.0, m.meanLatenessMinutes)
    }

    @Test
    fun `work started before shift end is finished as overtime, nothing new starts after the shift`() {
        // 근무 08–12시. 11:50 퇴실 청소는 12:30까지(초과 30분). 12:10 퇴실은 그날 시작 못 하고 다음날 08:00에 시작
        val d = dataset(
            turnover("101", at(0, "11:50"), at(1, "15:00")) + turnover("102", at(0, "12:10"), at(0, "15:00")),
            housekeeperShift = "08:00" to "12:00",
        )
        val m = run(d)
        assertEquals(30, m.workload.getValue("HOUSEKEEPER").overtimeMinutes)
        assertEquals(1, m.late) // 102: 다음날 08:00–08:40 청소, 09:00–09:10 검사
    }

    @Test
    fun `work that cannot finish within the horizon is reported as not ready and incomplete`() {
        val d = dataset(turnover("101", at(1, "12:10"), at(1, "15:00")), housekeeperShift = "08:00" to "12:00")
        val m = run(d)
        assertEquals(1, m.notReadyByHorizonEnd)
        assertEquals(1, m.jobs.getValue("CLEANING").incomplete)
        assertEquals(0.0, m.onTimeReadinessRate)
    }

    @Test
    fun `same shift start moves housekeeping shifts to 09 00`() {
        // 06:00 퇴실, 마감 09:00. 기준: 청소 08:00–08:40, 검사자 근무 09:00부터 → 09:10 준비(10분 지연)
        // 대안 B: 청소 09:00–09:40 → 검사 09:40–09:50(50분 지연). 차이 40분 (검사자 근무가 병목을 일부 흡수)
        val d = dataset(turnover("101", at(0, "06:00"), at(0, "09:30")))
        val base = run(d, SimPolicy.BASELINE_FIFO)
        val same = run(d, SimPolicy.SAME_SHIFT_START)
        assertEquals(10.0, base.meanLatenessMinutes)
        assertEquals(50.0, same.meanLatenessMinutes)
    }

    @Test
    fun `stayover requests use housekeeping time and report wait`() {
        // 연박 손님 10:00 투숙 중 청소 요청. 하우스키퍼는 09:50–10:30 체크아웃 청소 중 → 30분 대기
        val stayover = stay("201", at(-1, "15:00"), at(3, "11:00"), realization(stayovers = listOf(StayoverRequest(day0, 20))))
        val d = dataset(turnover("101", at(0, "09:50"), at(1, "15:00")) + stayover)
        val m = run(d)
        assertEquals(1, m.stayover.requested)
        assertEquals(1, m.stayover.completed)
        assertEquals(30.0, m.stayover.meanWaitMinutes)
    }

    @Test
    fun `checkouts before the horizon and deadlines after it are not evaluated`() {
        val d = dataset(
            listOf(stay("101", at(-3, "15:00"), at(-1, "11:00")), stay("101", at(0, "15:00"), at(1, "11:00"))) +
                turnover("102", at(1, "11:00"), at(5, "15:00")),
        )
        val m = run(d)
        assertEquals(2, m.turnovers) // 101의 day1 퇴실, 102의 day1 퇴실 (101의 day−1 퇴실은 기간 밖)
        assertEquals(0, m.evaluatedArrivals) // 101은 다음 도착 없음, 102 다음 도착은 기간 밖
        assertNull(m.onTimeReadinessRate)
    }

    @Test
    fun `same dataset and policy give identical metrics`() {
        val d = dataset(turnover("101", at(0, "11:00"), at(0, "15:00")) + turnover("102", at(0, "11:00"), at(0, "13:00")), housekeepers = 2)
        SimPolicy.entries.forEach { policy -> assertEquals(run(d, policy), run(d, policy)) }
    }

    @Test
    fun `dataset validation rejects non-synthetic and malformed input`() {
        val good = dataset(turnover("101", at(0, "11:00"), at(0, "15:00")))
        assertTrue(good.problems().isEmpty())
        val bad = good.copy(metadata = good.metadata.copy(synthetic = false, generatorVersion = "gen-v9"))
        assertEquals(2, bad.problems().size)
    }
}
