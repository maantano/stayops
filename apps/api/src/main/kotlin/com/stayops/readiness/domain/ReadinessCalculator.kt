package com.stayops.readiness.domain

import com.stayops.housekeeping.domain.DurationSource
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.room.domain.RoomAvailabilityStatus
import com.stayops.room.domain.RoomOperationalStatus
import java.time.Duration
import java.time.Instant

/**
 * 객실 준비 위험 계산 (docs/v4.1/02 §8). 순수 함수: [ReadinessSnapshot] + 기준 시각 → [ReadinessResult].
 *
 * 남은 경로(critical path)를 객실 상태별로 직렬로 쌓는다.
 * - 투숙 중(OCCUPIED·STAYOVER_CLEANING): 예정 체크아웃까지 대기 → 체크아웃 청소(기본값) → 검사(기본값)
 * - 청소 대기(VACANT_DIRTY + 시작 전 청소 task): blocker 해제·예정 시각까지 대기 → 청소 → 검사(기본값)
 * - 검사 대기(VACANT_DIRTY + 시작 전 검사 task): blocker 해제·예정 시각까지 대기 → 검사
 * - 청소 중 / 검사 중: blocker 해제까지 대기 → 남은 작업 → (청소면) 검사
 *
 * 규칙: 이미 일한 시간은 다시 더하지 않는다(실작업 시간 = 경과 − blocker 구간). 동시에 활성인 blocker는 합산하지 않고
 * 가장 늦은 예상 해제 시각만 반영한다. 예상 해제 시각이 없거나 이미 지난 blocker, 지난 체크아웃, 담당자 없이 멈춘 작업,
 * 다음 단계 task 부재처럼 시간을 알 수 없는 경우는 추정하지 않고 UNKNOWN을 반환한다.
 * 인력 가용성(근무표)은 반영하지 않는다(규칙 4) — 시작 시각은 task 예정 시각 또는 현재 시각 기준이다.
 */
object ReadinessCalculator {

    fun calculate(snapshot: ReadinessSnapshot, settings: ReadinessSettings, now: Instant): ReadinessResult {
        val deadline = snapshot.scheduledCheckInAt.minus(Duration.ofMinutes(snapshot.operationalBufferMinutes.toLong()))
        val path = PathBuilder(snapshot, now)
        if (snapshot.availabilityStatus == RoomAvailabilityStatus.OUT_OF_SERVICE) path.flags += "CANNOT_CHECK_IN_OUT_OF_SERVICE"

        if (snapshot.operationalStatus == RoomOperationalStatus.READY) {
            return result(snapshot, settings, now, deadline, ReadinessGrade.READY, expectedReadyAt = null, path)
        }
        path.build()
        if (path.missing.isNotEmpty()) {
            return result(snapshot, settings, now, deadline, ReadinessGrade.UNKNOWN, expectedReadyAt = null, path)
        }
        val expected = path.cursor
        val risk = Duration.between(deadline, expected)
        val grade = when {
            risk > Duration.ZERO -> ReadinessGrade.AT_RISK
            risk > settings.warningMargin.negated() -> ReadinessGrade.WARNING
            else -> ReadinessGrade.ON_TRACK
        }
        return result(snapshot, settings, now, deadline, grade, expected, path)
    }

    private fun result(
        s: ReadinessSnapshot,
        settings: ReadinessSettings,
        now: Instant,
        deadline: Instant,
        grade: ReadinessGrade,
        expectedReadyAt: Instant?,
        path: PathBuilder,
    ) = ReadinessResult(
        roomId = s.roomId, roomNumber = s.roomNumber, reservationId = s.reservationId, grade = grade,
        scheduledCheckInAt = s.scheduledCheckInAt, readyDeadline = deadline, expectedReadyAt = expectedReadyAt,
        riskMinutes = expectedReadyAt?.let { Math.ceilDiv(Duration.between(deadline, it).seconds, 60L) },
        availabilityStatus = s.availabilityStatus,
        // UNKNOWN이어도 판정 불가 지점까지의 기여는 설명용으로 남긴다(합계 일관성은 expectedReadyAt이 있을 때만 성립).
        contributions = path.contributions.toList(),
        overlappedBlockers = path.overlapped.toList(), missingInputs = path.missing.toList(), flags = path.flags.distinct(),
        assumptions = path.assumptions.distinct(), inputSnapshot = s, calculationVersion = settings.calculationVersion, calculatedAt = now,
    )

    private class PathBuilder(private val s: ReadinessSnapshot, private val now: Instant) {
        var cursor: Instant = now
        val contributions = mutableListOf<Contribution>()
        val missing = mutableListOf<String>()
        val flags = mutableListOf<String>()
        val assumptions = mutableListOf<String>()
        val overlapped = mutableListOf<java.util.UUID>()

        fun build() {
            when (s.operationalStatus) {
                RoomOperationalStatus.OCCUPIED, RoomOperationalStatus.STAYOVER_CLEANING -> occupied()
                RoomOperationalStatus.VACANT_DIRTY -> vacantDirty()
                RoomOperationalStatus.CLEANING -> inProgress(expected = TaskType.CHECKOUT_CLEANING)
                RoomOperationalStatus.INSPECTING -> inProgress(expected = TaskType.INSPECTION)
                RoomOperationalStatus.READY -> Unit
            }
        }

        private fun occupied() {
            val checkOut = s.currentStayCheckOutAt
            if (checkOut == null) return unknown("currentStay.scheduledCheckOutAt")
            if (!checkOut.isAfter(now)) return unknown("currentStay.actualCheckOutAt (scheduled check-out has passed)")
            assumptions += "현재 투숙객이 예정 체크아웃 시각에 퇴실한다고 가정"
            waitUntil(checkOut, ContributionType.WAIT_FOR_CHECKOUT, null, "예정 체크아웃")
            work(ContributionType.CLEANING, default(TaskType.CHECKOUT_CLEANING), null, DurationSource.CONFIG_DEFAULT, "체크아웃 청소(아직 생성 전)")
            inspectionAfterCleaning()
        }

        private fun vacantDirty() {
            val task = s.activeTask
            if (task == null) {
                return unknown(if (s.awaitingInspection) "activeTask (inspection task missing)" else "activeTask (cleaning task missing)")
            }
            if (task.actualStartedAt != null) return unknown("activeTask (started task on a VACANT_DIRTY room)")
            if (task.type == TaskType.STAYOVER_CLEANING) return unknown("activeTask (stayover task on a vacant room)")
            if (!task.hasAssignee) flags += "UNASSIGNED"
            if (!waitForBlockers(task)) return
            waitUntil(task.scheduledAt, ContributionType.WAIT_FOR_SCHEDULE, task.taskId, "작업 예정 시각")
            if (task.type == TaskType.INSPECTION) {
                work(ContributionType.INSPECTION, Duration.ofMinutes(task.estimatedDurationMinutes.toLong()), task.taskId, task.durationSource, null)
                assumptions += "검사 통과를 가정 (실패 시 재청소·재검사 시간 미반영)"
            } else {
                work(ContributionType.CLEANING, Duration.ofMinutes(task.estimatedDurationMinutes.toLong()), task.taskId, task.durationSource, null)
                inspectionAfterCleaning()
            }
        }

        private fun inProgress(expected: TaskType) {
            val task = s.activeTask
            val matches = task != null && task.actualStartedAt != null &&
                (task.type == expected || (expected == TaskType.CHECKOUT_CLEANING && task.type == TaskType.RE_CLEANING))
            if (!matches) return unknown("activeTask (no started ${if (expected == TaskType.INSPECTION) "inspection" else "cleaning"} task)")
            task!!
            if (!task.hasAssignee && task.status == TaskStatus.PENDING) {
                flags += "WORK_INTERRUPTED_NO_ASSIGNEE"
                return unknown("activeTask.assignee (work interrupted, resume time unknown)")
            }
            if (!waitForBlockers(task)) return
            val estimated = Duration.ofMinutes(task.estimatedDurationMinutes.toLong())
            val remaining = estimated.minus(workedSoFar(task))
            if (!remaining.isPositive) flags += "OVERRUN"
            val type = if (task.type == TaskType.INSPECTION) ContributionType.INSPECTION else ContributionType.CLEANING
            work(type, if (remaining.isPositive) remaining else Duration.ZERO, task.taskId, task.durationSource, "남은 작업(실작업 시간 차감)")
            if (task.type == TaskType.INSPECTION) {
                assumptions += "검사 통과를 가정 (실패 시 재청소·재검사 시간 미반영)"
            } else {
                inspectionAfterCleaning()
            }
        }

        /** 활성 blocker를 반영한다. 시간을 알 수 없으면 false (UNKNOWN). */
        private fun waitForBlockers(task: ReadinessSnapshot.TaskSnapshot): Boolean {
            val active = task.blockers.filter { it.isActive }
            if (active.isEmpty()) return true
            val noEstimate = active.filter { it.estimatedResolutionAt == null }
            if (noEstimate.isNotEmpty()) {
                noEstimate.forEach { unknown("blocker.estimatedResolutionAt (${it.type} ${it.blockerId})") }
                return false
            }
            val overdue = active.filter { !it.estimatedResolutionAt!!.isAfter(now) }
            if (overdue.isNotEmpty()) {
                overdue.forEach { unknown("blocker.estimatedResolutionAt (${it.type} ${it.blockerId} estimate has passed)") }
                return false
            }
            val latest = active.maxBy { it.estimatedResolutionAt!! }
            overlapped += active.filter { it.blockerId != latest.blockerId }.map { it.blockerId }
            waitUntil(latest.estimatedResolutionAt!!, ContributionType.BLOCKER, latest.blockerId, latest.type.name)
            return true
        }

        /** 실작업 시간 = 시작 이후 경과 − blocker로 멈춘 구간(겹침 제거). */
        private fun workedSoFar(task: ReadinessSnapshot.TaskSnapshot): Duration {
            val start = task.actualStartedAt!!
            val intervals = task.blockers
                .map { maxOf(it.blockedAt, start) to minOf(it.resolvedAt ?: now, now) }
                .filter { (from, to) -> to.isAfter(from) }
                .sortedBy { it.first }
            var blocked = Duration.ZERO
            var currentFrom: Instant? = null
            var currentTo: Instant? = null
            for ((from, to) in intervals) {
                if (currentTo == null || from.isAfter(currentTo)) {
                    if (currentFrom != null) blocked = blocked.plus(Duration.between(currentFrom, currentTo))
                    currentFrom = from
                    currentTo = to
                } else if (to.isAfter(currentTo)) {
                    currentTo = to
                }
            }
            if (currentFrom != null) blocked = blocked.plus(Duration.between(currentFrom, currentTo))
            val elapsed = Duration.between(start, now)
            return if (elapsed.isNegative) Duration.ZERO else elapsed.minus(blocked)
        }

        private fun inspectionAfterCleaning() {
            work(ContributionType.INSPECTION, default(TaskType.INSPECTION), null, DurationSource.CONFIG_DEFAULT, "청소 후 검사(아직 생성 전)")
            assumptions += "검사 통과를 가정 (실패 시 재청소·재검사 시간 미반영)"
        }

        private fun waitUntil(until: Instant, type: ContributionType, sourceId: java.util.UUID?, note: String?) {
            if (until.isAfter(cursor)) {
                contributions += Contribution(type, Duration.between(cursor, until).seconds, sourceId, null, note)
                cursor = until
            }
        }

        private fun work(type: ContributionType, duration: Duration, sourceId: java.util.UUID?, source: DurationSource?, note: String?) {
            contributions += Contribution(type, duration.seconds, sourceId, source, note)
            cursor = cursor.plus(duration)
        }

        private fun default(type: TaskType): Duration =
            Duration.ofMinutes(requireNotNull(s.defaultDurationMinutes[type]) { "default duration for $type" }.toLong())

        private fun unknown(input: String) {
            missing += input
        }
    }
}
