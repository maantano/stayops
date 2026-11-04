package com.stayops.housekeeping.application

import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.room.domain.Room
import com.stayops.room.domain.RoomOperationalStatus
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * "청소는 끝났고 아직 검사 결과가 없는가"를 task 이력으로 판정한다 (docs/v4.1/02 §2.10).
 * 검사 시작(P4), 재정비 검사 생성·workflowStage(P5)가 같은 구현을 쓴다. FE에서 다시 계산하지 않는다.
 */
@Component
class InspectionEligibility(private val em: EntityManager) {

    /**
     * 객실의 가장 최근 *started* cleaning task가 COMPLETED이고, 그것을 확인한 검사 결과가 없으면 그 task.
     * 시작 전 취소된 cleaning은 무시하고, 시작 후 취소된 cleaning이 가장 최근이면 null (부분 청소 불인정).
     */
    fun uninspectedCompletedCleaning(roomId: UUID): HousekeepingTask? {
        val latest = em.createQuery(
            """
            select t from HousekeepingTask t
            where t.roomId = :roomId and t.type in :cleaningTypes and t.actualStartedAt is not null
            order by t.actualStartedAt desc, t.createdAt desc
            """.trimIndent(),
            HousekeepingTask::class.java,
        ).setParameter("roomId", roomId)
            .setParameter("cleaningTypes", TaskType.entries.filter { it.isCleaning })
            .setMaxResults(1)
            .resultList
            .firstOrNull() ?: return null
        if (latest.status != TaskStatus.COMPLETED) return null

        val inspectedCount = em.createQuery(
            """
            select count(i) from InspectionRecord i
            where i.taskId in (select t.id from HousekeepingTask t where t.sourceTaskId = :cleaningId and t.type = :inspection)
            """.trimIndent(),
            java.lang.Long::class.java,
        ).setParameter("cleaningId", latest.getId())
            .setParameter("inspection", TaskType.INSPECTION)
            .singleResult
            .toLong()
        return if (inspectedCount == 0L) latest else null
    }

    fun hasActiveInspection(roomId: UUID): Boolean =
        em.createQuery(
            "select count(t) from HousekeepingTask t where t.roomId = :roomId and t.type = :inspection and t.status in :active",
            java.lang.Long::class.java,
        ).setParameter("roomId", roomId)
            .setParameter("inspection", TaskType.INSPECTION)
            .setParameter("active", TaskStatus.entries.filter { it.isActive })
            .singleResult
            .toLong() > 0

    /** 02 §2.10 `isAwaitingInspection(room)`. */
    fun isAwaitingInspection(room: Room): Boolean =
        room.operationalStatus == RoomOperationalStatus.VACANT_DIRTY &&
            uninspectedCompletedCleaning(room.getId()) != null &&
            !hasActiveInspection(room.getId())

    /** 검사 task를 처음 시작할 수 있는가: 그 검사가 확인할 cleaning이 바로 검사 대기 중인 cleaning이어야 한다. */
    fun canStartInspection(room: Room, inspection: HousekeepingTask): Boolean =
        room.operationalStatus == RoomOperationalStatus.VACANT_DIRTY &&
            inspection.sourceTaskId != null &&
            uninspectedCompletedCleaning(room.getId())?.getId() == inspection.sourceTaskId
}
