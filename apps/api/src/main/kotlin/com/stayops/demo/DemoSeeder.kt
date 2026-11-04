package com.stayops.demo

import com.stayops.housekeeping.application.RoomTaskCommandService
import com.stayops.housekeeping.application.TaskCommandResponse
import com.stayops.housekeeping.application.TaskCommandService
import com.stayops.housekeeping.domain.BlockerType
import com.stayops.housekeeping.domain.InspectionResult
import com.stayops.identity.Actor
import com.stayops.property.domain.Property
import com.stayops.property.infrastructure.PropertyRepository
import com.stayops.reservation.application.StayCommandResponse
import com.stayops.reservation.application.StayCommandService
import com.stayops.reservation.domain.Reservation
import com.stayops.reservation.domain.ReservationStatus
import com.stayops.reservation.infrastructure.ReservationRepository
import com.stayops.room.application.RoomAvailabilityService
import com.stayops.room.application.RoomCommandResponse
import com.stayops.room.domain.Room
import com.stayops.room.domain.RoomAvailabilityStatus
import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.room.infrastructure.RoomRepository
import com.stayops.shared.command.CommandContext
import com.stayops.shared.command.CommandRequest
import com.stayops.shared.command.CommandResult
import com.stayops.shared.command.IdempotentCommandExecutor
import com.stayops.staff.domain.Staff
import com.stayops.staff.domain.StaffRole
import com.stayops.staff.domain.StaffShift
import com.stayops.staff.infrastructure.StaffRepository
import com.stayops.staff.infrastructure.StaffShiftRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * 화면 확인용 데모 데이터 (P9). `demo` profile로 실행할 때만 동작하고, 데모 호텔이 이미 있으면 아무것도 하지 않는다.
 *
 * 기준 데이터(객실·직원·근무·투숙 예약)는 직접 저장하고, 이후 상태는 **실제 command**(체크아웃·배정·시작·차단·완료·검사)로
 * 만들어 이벤트 이력과 불변식이 실제 흐름과 같게 한다. 모든 값은 합성 데이터이며 실제 운영 데이터가 아니다.
 * 대량·재현 가능한 합성 데이터는 P10 Python generator가 담당한다.
 */
@Component
@Profile("demo")
class DemoSeeder(
    private val properties: PropertyRepository,
    private val rooms: RoomRepository,
    private val staffs: StaffRepository,
    private val shifts: StaffShiftRepository,
    private val reservations: ReservationRepository,
    private val executor: IdempotentCommandExecutor,
    private val stays: StayCommandService,
    private val tasks: TaskCommandService,
    private val roomTasks: RoomTaskCommandService,
    private val availability: RoomAvailabilityService,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (properties.findAll().any { it.name == HOTEL_NAME }) {
            log.info("[demo] '{}' already exists — skipping seed", HOTEL_NAME)
            return
        }
        val now = Instant.now()
        fun at(minutes: Long): Instant = now.plus(Duration.ofMinutes(minutes))

        val property = properties.save(Property(HOTEL_NAME, ZoneId.of("Asia/Seoul"), LocalTime.of(15, 0), LocalTime.of(11, 0), 30))
        val pid = property.getId()
        fun staff(name: String, role: StaffRole) = staffs.save(Staff(pid, name, role))
        val manager = staff("MGR-01 관리자", StaffRole.MANAGER)
        val frontDesk = staff("FD-01 프런트", StaffRole.FRONT_DESK)
        val hk1 = staff("HK-01 하우스키핑", StaffRole.HOUSEKEEPER)
        val hk2 = staff("HK-02 하우스키핑", StaffRole.HOUSEKEEPER)
        val hk3 = staff("HK-03 하우스키핑(근무 종료)", StaffRole.HOUSEKEEPER)
        val inspector = staff("IN-01 검사", StaffRole.INSPECTOR)
        listOf(hk1, hk2, inspector).forEach { shifts.save(StaffShift(it.getId(), at(-180), at(360), breakMinutes = 30)) }
        shifts.save(StaffShift(hk3.getId(), at(-540), at(-60), breakMinutes = 30))

        val types = listOf("STANDARD", "STANDARD", "DELUXE", "STANDARD", "SUITE", "STANDARD", "DELUXE", "STANDARD", "FAMILY", "STANDARD", "DELUXE", "SUITE")
        val r = (0 until 12).associate { i ->
            val number = "${i / 4 + 1}0${i % 4 + 1}"
            val initial = if (i < 2) RoomOperationalStatus.READY else RoomOperationalStatus.OCCUPIED
            number to rooms.save(Room(pid, number, types[i], i / 4 + 1, initial))
        }
        // 101~102 READY, 나머지는 투숙 중에서 시작한다.
        val stay = r.filterValues { it.operationalStatus == RoomOperationalStatus.OCCUPIED }.mapValues { (number, room) ->
            val checkOut = when (number) {
                "103" -> at(120)         // 2시간 뒤 체크아웃 예정, 3시간 뒤 도착 → 위험
                "104" -> at(60 * 30)     // 연박 → 투숙 중 청소 요청
                "201" -> at(-60)         // 예정 체크아웃이 지났는데 아직 투숙 → 판정 불가
                else -> at(-30)
            }
            reservations.save(
                Reservation(pid, room.getId(), at(-60 * 24), checkOut, guestCount = 2, status = ReservationStatus.CHECKED_IN, actualCheckInAt = at(-60 * 24)),
            )
        }

        fun actor(s: Staff) = Actor(s.getId(), pid, s.role)
        fun <T : Any> run(step: String, by: Staff, type: Class<T>, block: (CommandContext) -> T): T =
            executor.execute(CommandRequest("SEED $step", "seed-$step", UUID.randomUUID()), actor(by), step, type) { CommandResult(block(it)) }.body
        fun task(step: String, by: Staff, block: (CommandContext) -> TaskCommandResponse) = run(step, by, TaskCommandResponse::class.java, block)
        fun checkout(number: String) = run("checkout-$number", frontDesk, StayCommandResponse::class.java) { stays.checkOut(it, stay.getValue(number).getId()) }.checkoutCleaningTaskId!!

        // 102: 준비 완료지만 판매 중단
        run("oos-102", manager, RoomCommandResponse::class.java) { availability.change(it, r.getValue("102").getId(), RoomAvailabilityStatus.OUT_OF_SERVICE, "에어컨 점검 (합성)") }
        // 104: 투숙 중 청소 요청
        run("stayover-104", frontDesk, TaskCommandResponse::class.java) { roomTasks.createStayover(it, r.getValue("104").getId(), null, null, "수건 교체 요청 (합성)") }

        checkout("202") // 청소 대기 (미배정)
        val c203 = checkout("203") // 청소 중
        task("assign-203", manager) { tasks.assign(it, c203, hk1.getId()) }
        task("start-203", hk1) { tasks.start(it, c203) }
        val c204 = checkout("204") // 청소 중 차단(해제 예상 있음)
        task("assign-204", manager) { tasks.assign(it, c204, hk2.getId()) }
        task("start-204", hk2) { tasks.start(it, c204) }
        task("block-204", hk2) { tasks.addBlocker(it, c204, BlockerType.LINEN_SHORTAGE, "린넨 카트 대기", at(20)) }
        val c301 = checkout("301") // 청소 완료 → 검사 대기
        task("assign-301", manager) { tasks.assign(it, c301, hk1.getId()) }
        task("start-301", hk1) { tasks.start(it, c301) }
        val i301 = task("complete-301", hk1) { tasks.complete(it, c301) }.createdTaskId!!
        task("assign-i301", manager) { tasks.assign(it, i301, inspector.getId()) }
        val c302 = checkout("302") // 검사 중
        task("assign-302", manager) { tasks.assign(it, c302, hk2.getId()) }
        task("start-302", hk2) { tasks.start(it, c302) }
        val i302 = task("complete-302", hk2) { tasks.complete(it, c302) }.createdTaskId!!
        task("assign-i302", manager) { tasks.assign(it, i302, inspector.getId()) }
        task("start-i302", inspector) { tasks.start(it, i302) }
        val c303 = checkout("303") // 검사 실패 → 재청소 대기
        task("assign-303", manager) { tasks.assign(it, c303, hk1.getId()) }
        task("start-303", hk1) { tasks.start(it, c303) }
        val i303 = task("complete-303", hk1) { tasks.complete(it, c303) }.createdTaskId!!
        task("assign-i303", manager) { tasks.assign(it, i303, inspector.getId()) }
        task("start-i303", inspector) { tasks.start(it, i303) }
        task("fail-i303", inspector) { tasks.submitInspectionResult(it, i303, InspectionResult.FAILED, "욕실 바닥 물기 잔존 (합성)", mapOf("bathroom" to false, "bed" to true)) }
        val c304 = checkout("304") // 해제 예상 없는 DND 차단 → 판정 불가
        task("block-304", manager) { tasks.addBlocker(it, c304, BlockerType.DND, "방해 금지 표시", null) }

        // 도착 예정 (체크아웃 이후 등록해야 예약 구간 제약에 걸리지 않는다)
        val arrivals = mapOf(
            "101" to 180L, "102" to 240L, "103" to 180L, "201" to 300L, "202" to 60L, "203" to 300L,
            "204" to 120L, "301" to 360L, "302" to 150L, "303" to 240L, "304" to 300L,
        )
        arrivals.forEach { (number, minutes) ->
            reservations.save(Reservation(pid, r.getValue(number).getId(), at(minutes), at(minutes + 60 * 24), guestCount = 2))
        }
        log.info("[demo] seeded '{}' with {} rooms, {} staff (manager={}, frontDesk={})", HOTEL_NAME, r.size, 6, manager.getId(), frontDesk.getId())
    }

    companion object {
        const val HOTEL_NAME = "StayOps 데모 호텔 (합성 데이터)"
    }
}
