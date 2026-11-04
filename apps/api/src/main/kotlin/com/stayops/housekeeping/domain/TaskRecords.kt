package com.stayops.housekeeping.domain

import com.stayops.shared.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** 배정 이력 (append-only). 활성 배정은 `unassignedAt == null`. */
@Entity
@Table(name = "task_assignment")
class TaskAssignment(
    taskId: UUID,
    staffId: UUID,
    assignedAt: Instant,
    assignedBy: UUID,
    unassignedAt: Instant? = null,
    unassignedReason: UnassignedReason? = null,
    unassignedNote: String? = null,
    id: UUID = UUID.randomUUID(),
) : BaseEntity(id) {

    @Column(name = "task_id", nullable = false, updatable = false)
    val taskId: UUID = taskId

    @Column(name = "staff_id", nullable = false, updatable = false)
    val staffId: UUID = staffId

    @Column(name = "assigned_at", nullable = false, updatable = false)
    val assignedAt: Instant = assignedAt

    @Column(name = "assigned_by", nullable = false, updatable = false)
    val assignedBy: UUID = assignedBy

    @Column(name = "unassigned_at")
    var unassignedAt: Instant? = unassignedAt
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "unassigned_reason", length = 40)
    var unassignedReason: UnassignedReason? = unassignedReason
        protected set

    @Column(name = "unassigned_note", length = 500)
    var unassignedNote: String? = unassignedNote
        protected set

    val isActive: Boolean
        get() = unassignedAt == null

    fun end(at: Instant, reason: UnassignedReason, note: String? = null) {
        check(isActive) { "assignment ${getId()} is already ended" }
        unassignedAt = at
        unassignedReason = reason
        unassignedNote = note
    }
}

/** 작업 차단 사유. 해제해도 삭제하지 않고 resolvedAt·resolutionType으로 종결한다. */
@Entity
@Table(name = "task_blocker")
class TaskBlocker(
    taskId: UUID,
    type: BlockerType,
    blockedAt: Instant,
    description: String? = null,
    estimatedResolutionAt: Instant? = null,
    resolvedAt: Instant? = null,
    resolvedBy: UUID? = null,
    resolutionType: BlockerResolutionType? = null,
    resolutionNote: String? = null,
    id: UUID = UUID.randomUUID(),
) : BaseEntity(id) {

    @Column(name = "task_id", nullable = false, updatable = false)
    val taskId: UUID = taskId

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 30)
    val type: BlockerType = type

    @Column(name = "description", length = 500)
    var description: String? = description
        protected set

    @Column(name = "blocked_at", nullable = false, updatable = false)
    val blockedAt: Instant = blockedAt

    @Column(name = "estimated_resolution_at")
    var estimatedResolutionAt: Instant? = estimatedResolutionAt
        protected set

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = resolvedAt
        protected set

    @Column(name = "resolved_by")
    var resolvedBy: UUID? = resolvedBy
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_type", length = 20)
    var resolutionType: BlockerResolutionType? = resolutionType
        protected set

    @Column(name = "resolution_note", length = 500)
    var resolutionNote: String? = resolutionNote
        protected set

    val isActive: Boolean
        get() = resolvedAt == null

    /** 삭제하지 않고 종결한다. */
    fun close(at: Instant, by: UUID, type: BlockerResolutionType, note: String? = null) {
        check(isActive) { "blocker ${getId()} is already resolved" }
        resolvedAt = at
        resolvedBy = by
        resolutionType = type
        resolutionNote = note
    }
}

/** 검사 결과. 검사 task 하나당 한 건. */
@Entity
@Table(name = "inspection_record")
class InspectionRecord(
    taskId: UUID,
    result: InspectionResult,
    checklistSnapshotJson: String,
    inspectedBy: UUID,
    inspectedAt: Instant,
    failureReason: String? = null,
    id: UUID = UUID.randomUUID(),
) : BaseEntity(id) {

    @Column(name = "task_id", nullable = false, updatable = false, unique = true)
    val taskId: UUID = taskId

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, updatable = false, length = 10)
    val result: InspectionResult = result

    @Column(name = "failure_reason", updatable = false, length = 500)
    val failureReason: String? = failureReason

    /** JSON 문자열. 직렬화는 애플리케이션 계층 책임. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checklist_snapshot", nullable = false, updatable = false, columnDefinition = "jsonb")
    val checklistSnapshotJson: String = checklistSnapshotJson

    @Column(name = "inspected_by", nullable = false, updatable = false)
    val inspectedBy: UUID = inspectedBy

    @Column(name = "inspected_at", nullable = false, updatable = false)
    val inspectedAt: Instant = inspectedAt
}
