package com.stayops.housekeeping.domain

/**
 * 같은 group 안의 active task는 객실당 1개 (02 §2.9).
 * 병행 가능한 type을 추가할 때 다른 group을 부여한다.
 */
enum class WorkflowGroup {
    ROOM_WORKFLOW,
}

enum class TaskType(val workflowGroup: WorkflowGroup) {
    CHECKOUT_CLEANING(WorkflowGroup.ROOM_WORKFLOW),
    STAYOVER_CLEANING(WorkflowGroup.ROOM_WORKFLOW),
    RE_CLEANING(WorkflowGroup.ROOM_WORKFLOW),
    INSPECTION(WorkflowGroup.ROOM_WORKFLOW),
    ;

    val isCleaning: Boolean
        get() = this == CHECKOUT_CLEANING || this == RE_CLEANING
}

enum class TaskStatus {
    PENDING,
    ASSIGNED,
    IN_PROGRESS,
    BLOCKED,
    COMPLETED,
    CANCELLED,
    ;

    val isActive: Boolean
        get() = this != COMPLETED && this != CANCELLED

    val isTerminal: Boolean
        get() = !isActive
}

/** task가 생긴 원인. type("무슨 일")과 분리된 축이다 (v4.1 C10). */
enum class TaskOrigin {
    CHECKOUT_COMMAND,
    CLEANING_COMPLETED,
    INSPECTION_FAILED,
    STAYOVER_REQUEST,
    MANAGER_REWORK,
}

enum class DurationSource {
    USER_INPUT,
    CONFIG_DEFAULT,
}

enum class CancellationCode {
    MANUAL,
    CHECKOUT,
}

enum class UnassignedReason {
    REASSIGNED,
    ASSIGNEE_INVALID_ON_RESUME,
    TASK_CANCELLED,
    TASK_COMPLETED,
}

enum class BlockerType {
    DND,
    LINEN_SHORTAGE,
    SUPPLY_SHORTAGE,
    MAINTENANCE,
    STAFF_UNAVAILABLE,
    OTHER,
}

enum class BlockerResolutionType {
    RESOLVED,
    TASK_CANCELLED,
}

enum class InspectionResult {
    PASSED,
    FAILED,
}
