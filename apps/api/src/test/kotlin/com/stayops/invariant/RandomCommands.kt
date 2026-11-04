package com.stayops.invariant

import com.stayops.housekeeping.domain.HousekeepingTask
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.housekeeping.infrastructure.HousekeepingTaskRepository
import com.stayops.housekeeping.infrastructure.TaskAssignmentRepository
import com.stayops.housekeeping.infrastructure.TaskBlockerRepository
import com.stayops.reservation.domain.Reservation
import com.stayops.reservation.domain.ReservationStatus
import com.stayops.reservation.infrastructure.ReservationRepository
import com.stayops.room.domain.Room
import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.staff.domain.Staff
import com.stayops.staff.domain.StaffRole
import com.stayops.support.ApiClient
import com.stayops.support.StayWorld
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * seed로 재현 가능한 무작위 command 생성기 (docs/v4.1/05 §6).
 *
 * 일부러 잘못된 요청(권한 없는 역할, 맞지 않는 상태, 무효 담당자)도 섞는다. 서버는 이런 요청을 4xx로 거절해야 하고,
 * 어떤 순서에서도 5xx나 STATE_INCONSISTENT를 내면 안 된다. 생성은 현재 DB 상태를 보고 하므로 순차적으로 호출한다.
 */
class RandomCommands(
    seed: Long,
    private val hotel: StayWorld.Hotel,
    private val api: ApiClient,
    private val rooms: List<Room>,
    private val tasks: HousekeepingTaskRepository,
    private val assignments: TaskAssignmentRepository,
    private val blockers: TaskBlockerRepository,
    private val reservations: ReservationRepository,
    private val roomRepository: com.stayops.room.infrastructure.RoomRepository,
) {
    private val random = Random(seed)
    private val housekeeper2: Staff = hotel.staff(StaffRole.HOUSEKEEPER, "HK-02").also { hotel.onShift(it) }
    private val offShift: Staff = hotel.staff(StaffRole.HOUSEKEEPER, "HK-OFF").also {
        hotel.onShift(it, Instant.now().minus(Duration.ofHours(10)), Instant.now().minus(Duration.ofHours(2)))
    }
    private val staff = listOf(hotel.manager, hotel.frontDesk, hotel.housekeeper, housekeeper2, hotel.inspector, offShift)

    data class Command(val description: String, val run: () -> ApiClient.Response)

    /** 활성 예약이 없는 객실에 확정 예약을 보충해 체크인→체크아웃 순환이 계속되게 한다 (합성 데이터). */
    fun topUpReservations() {
        rooms.forEach { room ->
            val active = reservations.findByRoomIdOrderByScheduledCheckInAtAsc(room.getId()).any {
                it.status == ReservationStatus.CONFIRMED || it.status == ReservationStatus.CHECKED_IN
            }
            if (!active) hotel.reservation(room, checkIn = Instant.now(), checkOut = Instant.now().plus(Duration.ofDays(1)))
        }
    }

    fun next(): Command {
        val roll = random.nextInt(100)
        return when {
            roll < 60 -> taskCommand()
            roll < 85 -> roomCommand()
            else -> reservationCommand()
        }
    }

    // ------------------------------------------------------------------ task

    private fun taskCommand(): Command {
        val all = rooms.flatMap { tasks.findByRoomIdOrderByCreatedAtAsc(it.getId()) }
        if (all.isEmpty()) return roomCommand()
        val active = all.filter { it.status.isActive }
        val task = if (active.isNotEmpty() && random.nextInt(100) < 85) active.random(random) else all.random(random)
        val assignee = assignments.findByTaskIdAndUnassignedAtIsNull(task.getId())?.let { a -> staff.firstOrNull { it.getId() == a.staffId } }
        fun worker(): Staff = if (assignee != null && random.nextInt(100) < 75) assignee else staff.random(random)
        val base = "/api/v1/tasks/${task.getId()}"
        val label = "${task.type}(${task.status},started=${task.isStarted})"

        // 70%는 현재 상태에서 그럴듯한 다음 행동을 골라 깊은 상태(재시작, 검사 실패→재청소 등)까지 도달하게 하고,
        // 30%는 완전 무작위로 골라 잘못된 요청도 섞는다.
        val choice = if (random.nextInt(100) < 70) plausibleChoice(task) else random.nextInt(9)
        return when (choice) {
            0 -> {
                val target = if (random.nextInt(100) < 70) suitableStaff(task) else staff.random(random)
                cmd("assign $label to ${target.displayName}", hotel.manager) { api.post("$base/assignments", mapOf("staffId" to target.getId()), it, key()) }
            }
            1 -> {
                val assignmentId = assignments.findByTaskIdAndUnassignedAtIsNull(task.getId())?.getId() ?: UUID.randomUUID()
                cmd("unassign $label", hotel.manager) { api.delete("$base/assignments/$assignmentId", it) }
            }
            2, 3 -> worker().let { by -> cmd("start $label", by) { api.post("$base/start", null, it, key()) } }
            4 -> {
                val type = listOf("DND", "LINEN_SHORTAGE", "SUPPLY_SHORTAGE", "MAINTENANCE").random(random)
                val by = if (random.nextBoolean()) hotel.manager else worker()
                cmd("block $label $type", by) { api.post("$base/blockers", mapOf("type" to type), it, key()) }
            }
            5 -> {
                val blocker = blockers.findByTaskIdAndResolvedAtIsNullOrderByBlockedAtAsc(task.getId()).randomOrNull(random)
                val blockerId = blocker?.getId() ?: UUID.randomUUID()
                val by = if (random.nextBoolean()) hotel.manager else worker()
                cmd("resolve blocker of $label", by) { api.post("$base/blockers/$blockerId/resolve", null, it, key()) }
            }
            6 -> worker().let { by -> cmd("complete $label", by) { api.post("$base/complete", null, it, key()) } }
            7 -> {
                val result = if (random.nextBoolean()) "PASSED" else "FAILED"
                worker().let { by ->
                    cmd("inspect $label $result", by) {
                        api.post("$base/inspection-result", mapOf("result" to result, "failureReason" to if (result == "FAILED") "무작위 실패" else null), it, key())
                    }
                }
            }
            else -> {
                val by = listOf(hotel.manager, hotel.manager, hotel.frontDesk, worker()).random(random)
                cmd("cancel $label", by) { api.post("$base/cancel", mapOf("reason" to "무작위 취소"), it, key()) }
            }
        }
    }

    /** 0 assign, 1 unassign, 2·3 start, 4 block, 5 resolve, 6 complete, 7 inspection-result, 8 cancel. */
    private fun plausibleChoice(task: HousekeepingTask): Int = when (task.status) {
        TaskStatus.PENDING -> if (random.nextInt(100) < 85) 0 else 4
        TaskStatus.ASSIGNED -> listOf(2, 2, 2, 4, 1).random(random)
        TaskStatus.IN_PROGRESS -> when {
            random.nextInt(100) < 20 -> 4
            task.type == TaskType.INSPECTION -> 7
            else -> 6
        }
        TaskStatus.BLOCKED -> 5
        TaskStatus.COMPLETED, TaskStatus.CANCELLED -> random.nextInt(9)
    }

    /** 대체로 올바른 역할의 근무 중 직원, 가끔 근무 외 직원(SHIFT_CONFLICT·담당자 무효 경로). */
    private fun suitableStaff(task: HousekeepingTask): Staff = when {
        random.nextInt(100) < 15 -> offShift
        task.type == TaskType.INSPECTION -> hotel.inspector
        else -> listOf(hotel.housekeeper, housekeeper2).random(random)
    }

    // ------------------------------------------------------------------ room

    private fun roomCommand(): Command {
        val room = roomRepository.findById(rooms.random(random).getId()).orElseThrow()
        val base = "/api/v1/rooms/${room.getId()}"
        val label = "room ${room.roomNumber}(${room.operationalStatus},${room.availabilityStatus})"
        return when (random.nextInt(4)) {
            0 -> cmd("stayover $label", if (random.nextInt(100) < 85) hotel.frontDesk else staff.random(random)) {
                api.post("$base/stayover-cleanings", mapOf("notes" to "무작위 요청"), it, key())
            }
            1 -> {
                val kind = if (random.nextBoolean()) "CLEANING" else "INSPECTION"
                val assignee = if (random.nextInt(100) < 40) staff.random(random).getId() else null
                cmd("rework $kind $label", hotel.manager) {
                    api.post("$base/rework-tasks", mapOf("kind" to kind, "reason" to "무작위 재정비", "assigneeStaffId" to assignee), it, key())
                }
            }
            else -> {
                // OOS가 오래 유지되면 체크인 순환이 멈추므로 해제 쪽을 더 자주 고른다.
                val status = if (random.nextInt(100) < 35) "OUT_OF_SERVICE" else "AVAILABLE"
                cmd("availability $status $label", hotel.manager) { api.post("$base/availability", mapOf("status" to status, "reason" to "무작위"), it, key()) }
            }
        }
    }

    // ----------------------------------------------------------- reservation

    private fun reservationCommand(): Command {
        val all: List<Reservation> = rooms.flatMap { reservations.findByRoomIdOrderByScheduledCheckInAtAsc(it.getId()) }
        if (all.isEmpty()) return roomCommand()
        val by = if (random.nextInt(100) < 85) hotel.frontDesk else staff.random(random)
        return if (random.nextBoolean()) {
            val target = all.filter { it.status == ReservationStatus.CONFIRMED }.randomOrNull(random) ?: all.random(random)
            cmd("check-in ${target.status}", by) { api.post("/api/v1/reservations/${target.getId()}/check-in", null, it, key()) }
        } else {
            val target = all.filter { it.status == ReservationStatus.CHECKED_IN }.randomOrNull(random) ?: all.random(random)
            cmd("check-out ${target.status}", by) { api.post("/api/v1/reservations/${target.getId()}/check-out", null, it, key()) }
        }
    }

    private fun cmd(description: String, by: Staff, call: (UUID) -> ApiClient.Response) =
        Command("$description by ${by.displayName}", { call(by.getId()) })

    private fun key() = "rnd-" + UUID(random.nextLong(), random.nextLong())

    companion object {
        /** 객실 3개: 투숙 중, 준비 완료 + 확정 예약, 체크아웃 직후(청소 대기). */
        fun initialRooms(hotel: StayWorld.Hotel): List<Room> {
            val (occupied, _) = hotel.stay()
            val ready = hotel.room(RoomOperationalStatus.READY)
            val (dirty, _: HousekeepingTask) = hotel.turnover()
            return listOf(occupied, ready, dirty)
        }
    }
}
