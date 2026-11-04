package com.stayops.readiness.domain

import com.stayops.housekeeping.domain.BlockerType
import com.stayops.housekeeping.domain.DurationSource
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.room.domain.RoomAvailabilityStatus
import com.stayops.room.domain.RoomOperationalStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * readiness 계산 입력. 계산기는 이 스냅샷과 기준 시각만으로 결과를 낸다 (같은 입력 → 같은 결과).
 * 응답에 그대로 포함되어 계산 근거를 재현할 수 있게 한다 (docs/v4.1/02 §8 규칙 7).
 */
data class ReadinessSnapshot(
    val roomId: UUID,
    val roomNumber: String,
    val operationalStatus: RoomOperationalStatus,
    val availabilityStatus: RoomAvailabilityStatus,
    val workflowStage: String,
    val reservationId: UUID,
    val scheduledCheckInAt: Instant,
    val operationalBufferMinutes: Int,
    /** 현재 투숙(CHECKED_IN) 예약의 예정 체크아웃. 없으면 null. */
    val currentStayCheckOutAt: Instant?,
    val activeTask: TaskSnapshot?,
    /** 청소 완료 후 검사 결과가 없는 상태인지 (02 §2.10). */
    val awaitingInspection: Boolean,
    /** 아직 생성되지 않은 후속 단계(체크아웃 청소, 검사)에 쓸 설정 기본값(분). `ASSUMPTION`. */
    val defaultDurationMinutes: Map<TaskType, Int>,
) {
    data class TaskSnapshot(
        val taskId: UUID,
        val type: TaskType,
        val status: TaskStatus,
        val scheduledAt: Instant,
        val estimatedDurationMinutes: Int,
        val durationSource: DurationSource,
        val actualStartedAt: Instant?,
        val hasAssignee: Boolean,
        /** 해제된 것을 포함한 전체 blocker 이력 (실작업 시간 계산에 필요). */
        val blockers: List<BlockerSnapshot>,
    )

    data class BlockerSnapshot(
        val blockerId: UUID,
        val type: BlockerType,
        val blockedAt: Instant,
        val estimatedResolutionAt: Instant?,
        val resolvedAt: Instant?,
    ) {
        val isActive: Boolean get() = resolvedAt == null
    }
}

/** 02 §8 / v4.1 C6. 경계값은 설정이며 현장 표준이 아니다. */
enum class ReadinessGrade { READY, ON_TRACK, WARNING, AT_RISK, UNKNOWN }

enum class ContributionType {
    /** 현재 투숙객의 예정 체크아웃까지 대기 (예정 시각 퇴실 가정). */
    WAIT_FOR_CHECKOUT,
    /** 작업 예정 시각까지 대기. */
    WAIT_FOR_SCHEDULE,
    /** 활성 blocker의 예상 해제까지 대기. 여러 개면 가장 늦은 것만 시간에 반영. */
    BLOCKER,
    CLEANING,
    INSPECTION,
}

/** 예상 준비 시각까지의 시간 기여. 모든 기여의 합 = expectedReadyAt − calculatedAt. */
data class Contribution(
    val type: ContributionType,
    val seconds: Long,
    val sourceId: UUID?,
    val durationSource: DurationSource?,
    val note: String?,
) {
    val minutes: Long get() = Math.ceilDiv(seconds, 60L)
}

data class ReadinessResult(
    val roomId: UUID,
    val roomNumber: String,
    val reservationId: UUID,
    val grade: ReadinessGrade,
    val scheduledCheckInAt: Instant,
    val readyDeadline: Instant,
    val expectedReadyAt: Instant?,
    /** expectedReadyAt − readyDeadline (분, 올림). 양수면 마감 이후 완료 예상. */
    val riskMinutes: Long?,
    val availabilityStatus: RoomAvailabilityStatus,
    val contributions: List<Contribution>,
    /** 시간에 반영하지 않았지만 설명에 필요한 blocker (가장 늦은 blocker와 겹침). */
    val overlappedBlockers: List<UUID>,
    val missingInputs: List<String>,
    val flags: List<String>,
    val assumptions: List<String>,
    val inputSnapshot: ReadinessSnapshot,
    val calculationVersion: String,
    val calculatedAt: Instant,
)

data class ReadinessSettings(
    val warningMargin: Duration,
    val calculationVersion: String,
)
