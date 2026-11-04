package com.stayops.invariant

import com.stayops.housekeeping.domain.TaskRoomRules
import com.stayops.housekeeping.domain.TaskType
import com.stayops.room.domain.RoomOperationalStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * 한 property의 저장 상태가 도메인 불변식을 지키는지 DB에서 직접 검사한다 (docs/v4.1/05 §6).
 * DB 제약으로 이미 막히는 규칙도 서비스 회귀를 드러내기 위해 다시 확인한다. 위반 목록을 반환한다(비어 있으면 통과).
 */
class InvariantChecker(private val jdbc: JdbcTemplate) {

    fun violations(propertyId: UUID): List<String> = buildList {
        addAll(oneActiveTaskPerRoom(propertyId))
        addAll(blockedIffActiveBlocker(propertyId))
        addAll(assignmentMatchesStatus(propertyId))
        addAll(roomMatchesActiveTask(propertyId))
        addAll(occupancyMatchesReservation(propertyId))
        addAll(eventVersionsNotAhead(propertyId))
    }

    /** I1: 객실당 active task ≤ 1 (02 §2.9). */
    private fun oneActiveTaskPerRoom(propertyId: UUID) = jdbc.query(
        """
        select room_id, count(*) c from housekeeping_task
        where property_id = ? and status in ('PENDING','ASSIGNED','IN_PROGRESS','BLOCKED')
        group by room_id having count(*) > 1
        """.trimIndent(),
        { rs, _ -> "I1 room ${rs.getString("room_id")} has ${rs.getInt("c")} active tasks" },
        propertyId,
    )

    /** I2: BLOCKED ⇔ 활성 blocker ≥ 1 (02 §10.2, DB로 보장하지 않는 규칙). */
    private fun blockedIffActiveBlocker(propertyId: UUID) = jdbc.query(
        """
        select t.id, t.status,
               (select count(*) from task_blocker b where b.task_id = t.id and b.resolved_at is null) active_blockers
        from housekeeping_task t where t.property_id = ?
        """.trimIndent(),
        { rs, _ ->
            val blocked = rs.getString("status") == "BLOCKED"
            val active = rs.getInt("active_blockers")
            if (blocked != (active > 0)) "I2 task ${rs.getString("id")} status=${rs.getString("status")} activeBlockers=$active" else null
        },
        propertyId,
    ).filterNotNull()

    /**
     * I3: 활성 assignment 수가 상태와 맞는다. ASSIGNED/IN_PROGRESS(또는 그 상태에서 BLOCKED)는 1, 그 외(PENDING·terminal)는 0.
     * started인데 PENDING인 task(담당자 무효 복귀)도 0이어야 한다.
     */
    private fun assignmentMatchesStatus(propertyId: UUID) = jdbc.query(
        """
        select t.id, t.status, t.status_before_blocked,
               (select count(*) from task_assignment a where a.task_id = t.id and a.unassigned_at is null) active_assignments
        from housekeeping_task t where t.property_id = ?
        """.trimIndent(),
        { rs, _ ->
            val status = rs.getString("status")
            val before = rs.getString("status_before_blocked")
            val effective = if (status == "BLOCKED") before else status
            val expected = if (effective == "ASSIGNED" || effective == "IN_PROGRESS") 1 else 0
            val actual = rs.getInt("active_assignments")
            if (actual != expected) "I3 task ${rs.getString("id")} status=$status before=$before activeAssignments=$actual expected=$expected" else null
        },
        propertyId,
    ).filterNotNull()

    /**
     * I4: Room × active task matrix (02 §3.3).
     * - started active task → Room은 그 type의 진행 상태
     * - unstarted active task → Room은 그 type의 시작 전 상태
     * - active task 없음 → Room은 진행 상태(CLEANING·INSPECTING·STAYOVER_CLEANING)가 아님
     */
    private fun roomMatchesActiveTask(propertyId: UUID) = jdbc.query(
        """
        select r.id room_id, r.operational_status, t.id task_id, t.type, t.actual_started_at is not null started
        from room r
        left join housekeeping_task t on t.room_id = r.id and t.status in ('PENDING','ASSIGNED','IN_PROGRESS','BLOCKED')
        where r.property_id = ?
        """.trimIndent(),
        { rs, _ ->
            val room = RoomOperationalStatus.valueOf(rs.getString("operational_status"))
            val taskId = rs.getString("task_id")
            if (taskId == null) {
                if (room in IN_PROGRESS_ROOM_STATES) "I4 room ${rs.getString("room_id")} is $room without an active task" else null
            } else {
                val type = TaskType.valueOf(rs.getString("type"))
                val expected = if (rs.getBoolean("started")) TaskRoomRules.roomWhileInProgress(type) else TaskRoomRules.roomBeforeStart(type)
                if (room != expected) "I4 room ${rs.getString("room_id")} is $room but active $type task $taskId (started=${rs.getBoolean("started")}) expects $expected" else null
            }
        },
        propertyId,
    ).filterNotNull()

    /** I5: Room이 OCCUPIED·STAYOVER_CLEANING ⇔ 그 객실의 CHECKED_IN 예약이 정확히 1개. */
    private fun occupancyMatchesReservation(propertyId: UUID) = jdbc.query(
        """
        select r.id, r.operational_status,
               (select count(*) from reservation v where v.room_id = r.id and v.status = 'CHECKED_IN') checked_in
        from room r where r.property_id = ?
        """.trimIndent(),
        { rs, _ ->
            val occupied = rs.getString("operational_status") in setOf("OCCUPIED", "STAYOVER_CLEANING")
            val checkedIn = rs.getInt("checked_in")
            if (occupied != (checkedIn == 1)) "I5 room ${rs.getString("id")} is ${rs.getString("operational_status")} with $checkedIn CHECKED_IN reservations" else null
        },
        propertyId,
    ).filterNotNull()

    /** I6: 이벤트의 aggregateVersion이 aggregate의 현재 version을 넘지 않는다 (이벤트가 commit되지 않은 변경을 가리키지 않음). */
    private fun eventVersionsNotAhead(propertyId: UUID) = jdbc.query(
        """
        select e.aggregate_type, e.aggregate_id, max(e.aggregate_version) max_event_version,
               coalesce(r.version, v.version, t.version) current_version
        from operational_event e
        left join room r on e.aggregate_type = 'Room' and r.id = e.aggregate_id
        left join reservation v on e.aggregate_type = 'Reservation' and v.id = e.aggregate_id
        left join housekeeping_task t on e.aggregate_type = 'HousekeepingTask' and t.id = e.aggregate_id
        where e.property_id = ?
        group by e.aggregate_type, e.aggregate_id, r.version, v.version, t.version
        """.trimIndent(),
        { rs, _ ->
            val maxEvent = rs.getLong("max_event_version")
            val current = rs.getLong("current_version")
            if (maxEvent > current) "I6 ${rs.getString("aggregate_type")} ${rs.getString("aggregate_id")} event version $maxEvent > current $current" else null
        },
        propertyId,
    ).filterNotNull()

    companion object {
        private val IN_PROGRESS_ROOM_STATES = setOf(
            RoomOperationalStatus.CLEANING, RoomOperationalStatus.INSPECTING, RoomOperationalStatus.STAYOVER_CLEANING,
        )
    }
}
