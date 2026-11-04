package com.stayops.workflow.lock

import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.reservation.domain.Reservation
import com.stayops.reservation.domain.ReservationStatus
import com.stayops.room.domain.Room
import com.stayops.shared.error.NotFoundException
import com.stayops.shared.error.StateInconsistencyException
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 객실 workflow command의 잠금 진입점 (ADR-003).
 *
 * 잠금 순서: Room → Reservation → HousekeepingTask. [RoomLockScope]가 순서를 강제한다.
 * 잠금은 `EntityManager.find(..., PESSIMISTIC_WRITE)`로 획득한다. 같은 transaction에서 이미 읽은
 * 엔티티라도 JPA 명세상 version 검사가 수행되므로 오래된 상태로 판단하지 않는다.
 * 잠금 대기 상한은 `stayops.db.lock-timeout`(연결 초기화 시 `SET lock_timeout`)이며 초과 시 409 VERSION_CONFLICT.
 */
@Component
class RoomWorkflowLocks(private val em: EntityManager) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun lockRoom(propertyId: UUID, roomId: UUID): RoomLockScope {
        val room = em.find(Room::class.java, roomId, LockModeType.PESSIMISTIC_WRITE)
        if (room == null || room.propertyId != propertyId) throw NotFoundException("room", roomId)
        return RoomLockScope(room, em)
    }

    /** task id만 아는 command용. 잠그지 않고 roomId만 읽은 뒤 Room부터 순서대로 잠근다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockRoomOfTask(propertyId: UUID, taskId: UUID): Pair<RoomLockScope, HousekeepingTask> {
        val roomId = scalarRoomId("select t.roomId from HousekeepingTask t where t.id = :id and t.propertyId = :propertyId", taskId, propertyId)
            ?: throw NotFoundException("task", taskId)
        val scope = lockRoom(propertyId, roomId)
        return scope to scope.task(taskId)
    }

    /**
     * task command 공통 진입점: Room → (객실의 CHECKED_IN 예약) → Task 순서로 잠근다.
     * stayover 시작처럼 예약 상태를 함께 확인해야 하는 command가 순서를 어기지 않도록 예약을 항상 먼저 잠근다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockForTaskCommand(propertyId: UUID, taskId: UUID): TaskLock {
        val roomId = scalarRoomId("select t.roomId from HousekeepingTask t where t.id = :id and t.propertyId = :propertyId", taskId, propertyId)
            ?: throw NotFoundException("task", taskId)
        val scope = lockRoom(propertyId, roomId)
        val checkedIn = scope.checkedInReservation()
        return TaskLock(scope, checkedIn, scope.task(taskId))
    }

    /** reservation id만 아는 command용. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockRoomOfReservation(propertyId: UUID, reservationId: UUID): Pair<RoomLockScope, Reservation> {
        val roomId = scalarRoomId(
            "select r.roomId from Reservation r where r.id = :id and r.propertyId = :propertyId", reservationId, propertyId,
        ) ?: throw NotFoundException("reservation", reservationId)
        val scope = lockRoom(propertyId, roomId)
        return scope to scope.reservation(reservationId)
    }

    private fun scalarRoomId(jpql: String, id: UUID, propertyId: UUID): UUID? =
        em.createQuery(jpql, UUID::class.java)
            .setParameter("id", id)
            .setParameter("propertyId", propertyId)
            .resultList
            .firstOrNull()
}

data class TaskLock(val scope: RoomLockScope, val checkedInReservation: Reservation?, val task: HousekeepingTask) {
    val room: Room get() = scope.room
}

class RoomLockScope internal constructor(val room: Room, private val em: EntityManager) {

    private enum class Level { ROOM, RESERVATION, TASK }

    private var level = Level.ROOM

    fun reservation(reservationId: UUID): Reservation {
        advance(Level.RESERVATION)
        val reservation = em.find(Reservation::class.java, reservationId, LockModeType.PESSIMISTIC_WRITE)
            ?: throw NotFoundException("reservation", reservationId)
        if (reservation.roomId != room.getId()) {
            throw StateInconsistencyException(
                "reservation moved to another room while locking",
                mapOf("reservationId" to reservationId, "expectedRoomId" to room.getId(), "actualRoomId" to reservation.roomId),
            )
        }
        return reservation
    }

    /** 이 객실의 CHECKED_IN 예약 (DB 제약상 최대 1개). */
    fun checkedInReservation(): Reservation? {
        advance(Level.RESERVATION)
        val id = em.createQuery(
            "select r.id from Reservation r where r.roomId = :roomId and r.status = :status",
            UUID::class.java,
        ).setParameter("roomId", room.getId())
            .setParameter("status", ReservationStatus.CHECKED_IN)
            .resultList
            .firstOrNull() ?: return null
        return em.find(Reservation::class.java, id, LockModeType.PESSIMISTIC_WRITE)
    }

    fun task(taskId: UUID): HousekeepingTask {
        advance(Level.TASK)
        val task = em.find(HousekeepingTask::class.java, taskId, LockModeType.PESSIMISTIC_WRITE)
        if (task == null || task.roomId != room.getId()) throw NotFoundException("task", taskId)
        return task
    }

    /** 이 객실의 active workflow task (DB 제약상 최대 1개, v4.1 02 §2.9). */
    fun activeTask(): HousekeepingTask? {
        advance(Level.TASK)
        val id = em.createQuery(
            "select t.id from HousekeepingTask t where t.roomId = :roomId and t.status in :active",
            UUID::class.java,
        ).setParameter("roomId", room.getId())
            .setParameter("active", TaskStatus.entries.filter { it.isActive })
            .resultList
            .firstOrNull() ?: return null
        return em.find(HousekeepingTask::class.java, id, LockModeType.PESSIMISTIC_WRITE)
    }

    private fun advance(next: Level) {
        check(next >= level) { "lock order violation: $level -> $next (required: Room -> Reservation -> Task)" }
        level = next
    }
}
