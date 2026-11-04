package com.stayops.housekeeping.domain

import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.staff.domain.StaffRole

/**
 * Room × Task lifecycle matrix (docs/v4.1/02 §3.3)를 코드로 옮긴 표.
 * 서비스는 이 값과 실제 Room 상태를 비교하고, 다르면 보정하지 않고 STATE_INCONSISTENT로 거부한다.
 */
object TaskRoomRules {

    /** 최초 start 시 Room이 있어야 하는 상태. */
    fun roomBeforeStart(type: TaskType): RoomOperationalStatus = when (type) {
        TaskType.CHECKOUT_CLEANING, TaskType.RE_CLEANING, TaskType.INSPECTION -> RoomOperationalStatus.VACANT_DIRTY
        TaskType.STAYOVER_CLEANING -> RoomOperationalStatus.OCCUPIED
    }

    /** start 후(차단·담당자 무효 복귀 중 포함) Room 상태. 재시작 시에도 이 상태여야 한다. */
    fun roomWhileInProgress(type: TaskType): RoomOperationalStatus = when (type) {
        TaskType.CHECKOUT_CLEANING, TaskType.RE_CLEANING -> RoomOperationalStatus.CLEANING
        TaskType.INSPECTION -> RoomOperationalStatus.INSPECTING
        TaskType.STAYOVER_CLEANING -> RoomOperationalStatus.STAYOVER_CLEANING
    }

    /** 시작 후 취소 시 Room 복귀 상태. 시작 전 취소는 Room을 바꾸지 않는다. */
    fun roomAfterCancelAfterStart(type: TaskType): RoomOperationalStatus = when (type) {
        TaskType.CHECKOUT_CLEANING, TaskType.RE_CLEANING, TaskType.INSPECTION -> RoomOperationalStatus.VACANT_DIRTY
        TaskType.STAYOVER_CLEANING -> RoomOperationalStatus.OCCUPIED
    }

    /** complete 후 Room 상태. INSPECTION은 결과(PASSED/FAILED)에 따라 다르므로 inspection-result에서 처리한다. */
    fun roomAfterComplete(type: TaskType): RoomOperationalStatus = when (type) {
        TaskType.CHECKOUT_CLEANING, TaskType.RE_CLEANING -> RoomOperationalStatus.VACANT_DIRTY
        TaskType.STAYOVER_CLEANING -> RoomOperationalStatus.OCCUPIED
        TaskType.INSPECTION -> error("INSPECTION completes through inspection-result")
    }

    /** 배정 가능 역할 (02 §2 Staff). MANAGER·FRONT_DESK는 담당자가 될 수 없다. */
    fun assigneeRole(type: TaskType): StaffRole = when (type) {
        TaskType.INSPECTION -> StaffRole.INSPECTOR
        TaskType.CHECKOUT_CLEANING, TaskType.RE_CLEANING, TaskType.STAYOVER_CLEANING -> StaffRole.HOUSEKEEPER
    }
}
