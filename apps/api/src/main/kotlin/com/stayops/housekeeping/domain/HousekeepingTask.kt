package com.stayops.housekeeping.domain

import com.stayops.shared.domain.AuditedEntity
import com.stayops.shared.error.ErrorCode
import com.stayops.shared.error.StayOpsException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 하우스키핑 작업 (02 §2 HousekeepingTask).
 *
 * 상태 전이 메서드는 P3~P5에서 추가한다. 현재는 스키마와 1:1인 영속 모델이며,
 * 상태-필드 불변식은 DB CHECK 제약이 최종 보장한다 (V1__core_schema.sql).
 */
@Entity
@Table(name = "housekeeping_task")
class HousekeepingTask(
    propertyId: UUID,
    roomId: UUID,
    type: TaskType,
    origin: TaskOrigin,
    scheduledAt: Instant,
    estimatedDurationMinutes: Int,
    durationSource: DurationSource,
    createdBy: UUID,
    reservationId: UUID? = null,
    sourceTaskId: UUID? = null,
    originReason: String? = null,
    status: TaskStatus = TaskStatus.PENDING,
    statusBeforeBlocked: TaskStatus? = null,
    dueAt: Instant? = null,
    actualStartedAt: Instant? = null,
    actualCompletedAt: Instant? = null,
    cancellationCode: CancellationCode? = null,
    cancellationReason: String? = null,
    notes: String? = null,
    id: UUID = UUID.randomUUID(),
) : AuditedEntity(id) {

    @Column(name = "property_id", nullable = false, updatable = false)
    val propertyId: UUID = propertyId

    @Column(name = "room_id", nullable = false, updatable = false)
    val roomId: UUID = roomId

    @Column(name = "reservation_id", updatable = false)
    val reservationId: UUID? = reservationId

    /** INSPECTION → 확인 대상 cleaning task, RE_CLEANING(INSPECTION_FAILED) → 실패한 검사 task. */
    @Column(name = "source_task_id", updatable = false)
    val sourceTaskId: UUID? = sourceTaskId

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 30)
    val type: TaskType = type

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_group", nullable = false, updatable = false, length = 30)
    val workflowGroup: WorkflowGroup = type.workflowGroup

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, updatable = false, length = 30)
    val origin: TaskOrigin = origin

    @Column(name = "origin_reason", updatable = false, length = 500)
    val originReason: String? = originReason

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: TaskStatus = status
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status_before_blocked", length = 20)
    var statusBeforeBlocked: TaskStatus? = statusBeforeBlocked
        protected set

    @Column(name = "scheduled_at", nullable = false)
    var scheduledAt: Instant = scheduledAt
        protected set

    @Column(name = "due_at")
    var dueAt: Instant? = dueAt
        protected set

    @Column(name = "estimated_duration_minutes", nullable = false)
    var estimatedDurationMinutes: Int = estimatedDurationMinutes
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "duration_source", nullable = false, length = 20)
    var durationSource: DurationSource = durationSource
        protected set

    /** "시작 전/후" 판정의 유일한 기준 (02 §2.8). 재시작 시에도 최초 시각을 보존한다. */
    @Column(name = "actual_started_at")
    var actualStartedAt: Instant? = actualStartedAt
        protected set

    @Column(name = "actual_completed_at")
    var actualCompletedAt: Instant? = actualCompletedAt
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_code", length = 20)
    var cancellationCode: CancellationCode? = cancellationCode
        protected set

    @Column(name = "cancellation_reason", length = 500)
    var cancellationReason: String? = cancellationReason
        protected set

    @Column(name = "created_by", nullable = false, updatable = false)
    val createdBy: UUID = createdBy

    @Column(name = "notes", length = 500)
    var notes: String? = notes
        protected set

    val isStarted: Boolean
        get() = actualStartedAt != null

    /** PENDING → ASSIGNED, 또는 ASSIGNED 재배정. IN_PROGRESS 재배정은 MVP에서 금지 (02 §4 Task assign). */
    fun markAssigned() {
        if (status != TaskStatus.PENDING && status != TaskStatus.ASSIGNED) {
            throw StayOpsException(ErrorCode.INVALID_STATE, "task cannot be assigned while $status", mapOf("currentStatus" to status.name))
        }
        status = TaskStatus.ASSIGNED
    }

    /** ASSIGNED → PENDING (관리자 배정 해제). */
    fun markUnassigned() {
        if (status != TaskStatus.ASSIGNED) {
            throw StayOpsException(ErrorCode.INVALID_STATE, "task cannot be unassigned while $status", mapOf("currentStatus" to status.name))
        }
        status = TaskStatus.PENDING
    }

    /** ASSIGNED → IN_PROGRESS. 재시작이면 최초 시작 시각을 보존한다 (02 §2.8). 선행조건은 서비스가 판정한다. */
    fun start(at: Instant) {
        check(status == TaskStatus.ASSIGNED) { "start requires ASSIGNED but was $status" }
        status = TaskStatus.IN_PROGRESS
        if (actualStartedAt == null) actualStartedAt = at
    }

    /** 첫 활성 blocker 등록 시 현재 상태를 저장하고 BLOCKED로. 이미 BLOCKED면 저장 값을 유지한다. */
    fun block() {
        check(status.isActive) { "cannot block a $status task" }
        if (status != TaskStatus.BLOCKED) {
            statusBeforeBlocked = status
            status = TaskStatus.BLOCKED
        }
    }

    /** 마지막 blocker 해제 시 복귀. [to]는 저장된 상태이거나, 담당자가 무효면 PENDING. */
    fun unblock(to: TaskStatus) {
        check(status == TaskStatus.BLOCKED) { "unblock requires BLOCKED but was $status" }
        check(to == TaskStatus.PENDING || to == TaskStatus.ASSIGNED || to == TaskStatus.IN_PROGRESS) { "invalid unblock target $to" }
        status = to
        statusBeforeBlocked = null
    }

    fun complete(at: Instant) {
        if (status != TaskStatus.IN_PROGRESS) {
            throw StayOpsException(ErrorCode.INVALID_STATE, "task cannot be completed while $status", mapOf("currentStatus" to status.name))
        }
        status = TaskStatus.COMPLETED
        actualCompletedAt = at
    }

    /**
     * active → CANCELLED. 권한·Room 복귀 판단은 호출하는 서비스 책임이다.
     * assignment 종료와 blocker 종결도 같은 transaction에서 서비스가 처리한다.
     */
    fun cancel(code: CancellationCode, reason: String?) {
        if (!status.isActive) {
            throw StayOpsException(ErrorCode.INVALID_STATE, "task is $status", mapOf("currentStatus" to status.name))
        }
        status = TaskStatus.CANCELLED
        statusBeforeBlocked = null
        cancellationCode = code
        cancellationReason = reason
    }

    companion object {
        /** 체크아웃 command 내부에서만 생성한다 (v4.1 02 §4 Task create). */
        fun checkoutCleaning(
            propertyId: UUID,
            roomId: UUID,
            reservationId: UUID,
            scheduledAt: Instant,
            estimatedDurationMinutes: Int,
            durationSource: DurationSource,
            createdBy: UUID,
        ) = HousekeepingTask(
            propertyId = propertyId,
            roomId = roomId,
            type = TaskType.CHECKOUT_CLEANING,
            origin = TaskOrigin.CHECKOUT_COMMAND,
            scheduledAt = scheduledAt,
            estimatedDurationMinutes = estimatedDurationMinutes,
            durationSource = durationSource,
            createdBy = createdBy,
            reservationId = reservationId,
        )

        /** 투숙 중 청소 요청 (02 §4 Stayover create). */
        fun stayover(
            propertyId: UUID,
            roomId: UUID,
            reservationId: UUID,
            scheduledAt: Instant,
            estimatedDurationMinutes: Int,
            durationSource: DurationSource,
            createdBy: UUID,
            notes: String?,
        ) = HousekeepingTask(
            propertyId = propertyId, roomId = roomId, type = TaskType.STAYOVER_CLEANING, origin = TaskOrigin.STAYOVER_REQUEST,
            scheduledAt = scheduledAt, estimatedDurationMinutes = estimatedDurationMinutes, durationSource = durationSource,
            createdBy = createdBy, reservationId = reservationId, notes = notes,
        )

        /** 검사 FAILED 결과 command 내부에서 생성. sourceTaskId는 실패한 검사 task. */
        fun reCleaningAfter(
            failedInspection: HousekeepingTask,
            scheduledAt: Instant,
            estimatedDurationMinutes: Int,
            durationSource: DurationSource,
            createdBy: UUID,
        ): HousekeepingTask {
            require(failedInspection.type == TaskType.INSPECTION) { "re-cleaning requires an inspection task" }
            return HousekeepingTask(
                propertyId = failedInspection.propertyId, roomId = failedInspection.roomId, type = TaskType.RE_CLEANING,
                origin = TaskOrigin.INSPECTION_FAILED, scheduledAt = scheduledAt, estimatedDurationMinutes = estimatedDurationMinutes,
                durationSource = durationSource, createdBy = createdBy, sourceTaskId = failedInspection.getId(),
            )
        }

        /**
         * 관리자 재정비 (02 §4 Rework create). CLEANING은 RE_CLEANING(sourceTaskId 없음),
         * INSPECTION은 확인 대상 cleaning을 sourceTaskId로 갖는다.
         */
        fun managerRework(
            propertyId: UUID,
            roomId: UUID,
            type: TaskType,
            reason: String,
            inspectedCleaningId: UUID?,
            scheduledAt: Instant,
            estimatedDurationMinutes: Int,
            durationSource: DurationSource,
            createdBy: UUID,
        ): HousekeepingTask {
            require(type == TaskType.RE_CLEANING || type == TaskType.INSPECTION) { "rework supports RE_CLEANING or INSPECTION" }
            require((type == TaskType.INSPECTION) == (inspectedCleaningId != null)) { "inspection rework needs the cleaning it verifies" }
            return HousekeepingTask(
                propertyId = propertyId, roomId = roomId, type = type, origin = TaskOrigin.MANAGER_REWORK, originReason = reason,
                scheduledAt = scheduledAt, estimatedDurationMinutes = estimatedDurationMinutes, durationSource = durationSource,
                createdBy = createdBy, sourceTaskId = inspectedCleaningId,
            )
        }

        /** cleaning complete command 내부에서 생성. sourceTaskId는 확인 대상 cleaning task (02 §2). */
        fun inspectionOf(
            cleaning: HousekeepingTask,
            scheduledAt: Instant,
            estimatedDurationMinutes: Int,
            durationSource: DurationSource,
            createdBy: UUID,
        ): HousekeepingTask {
            require(cleaning.type.isCleaning && cleaning.status == TaskStatus.COMPLETED) { "inspection requires a completed cleaning task" }
            return HousekeepingTask(
                propertyId = cleaning.propertyId,
                roomId = cleaning.roomId,
                type = TaskType.INSPECTION,
                origin = TaskOrigin.CLEANING_COMPLETED,
                scheduledAt = scheduledAt,
                estimatedDurationMinutes = estimatedDurationMinutes,
                durationSource = durationSource,
                createdBy = createdBy,
                sourceTaskId = cleaning.getId(),
            )
        }
    }
}
