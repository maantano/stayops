package com.stayops.workflow

import com.stayops.housekeeping.application.InspectionEligibility
import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.TaskType
import com.stayops.room.domain.Room
import com.stayops.room.domain.RoomOperationalStatus
import org.springframework.stereotype.Component

/** 서버가 계산하는 객실 업무 단계 (docs/v4.1/02 §2.10). 저장 enum이 아니며 FE는 다시 계산하지 않는다. */
enum class WorkflowStage {
    NEEDS_CLEANING,
    CLEANING,
    AWAITING_INSPECTION,
    INSPECTING,
    READY,
    OCCUPIED,
    STAYOVER_CLEANING,
}

@Component
class WorkflowStageService(private val inspections: InspectionEligibility) {

    /** [activeTask]는 객실의 active task(최대 1개) 또는 null. */
    fun stageOf(room: Room, activeTask: HousekeepingTask?): WorkflowStage = when (room.operationalStatus) {
        RoomOperationalStatus.VACANT_DIRTY ->
            if (inspections.isAwaitingInspection(room) || (activeTask?.type == TaskType.INSPECTION && !activeTask.isStarted)) {
                WorkflowStage.AWAITING_INSPECTION
            } else {
                WorkflowStage.NEEDS_CLEANING
            }
        RoomOperationalStatus.CLEANING -> WorkflowStage.CLEANING
        RoomOperationalStatus.INSPECTING -> WorkflowStage.INSPECTING
        RoomOperationalStatus.READY -> WorkflowStage.READY
        RoomOperationalStatus.OCCUPIED -> WorkflowStage.OCCUPIED
        RoomOperationalStatus.STAYOVER_CLEANING -> WorkflowStage.STAYOVER_CLEANING
    }
}
