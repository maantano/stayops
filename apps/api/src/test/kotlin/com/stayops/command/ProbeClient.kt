package com.stayops.command

import com.stayops.property.domain.Property
import com.stayops.property.infrastructure.PropertyRepository
import com.stayops.room.domain.Room
import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.room.infrastructure.RoomRepository
import com.stayops.staff.domain.Staff
import com.stayops.staff.domain.StaffRole
import com.stayops.staff.infrastructure.StaffRepository
import com.stayops.support.ApiClient
import tools.jackson.databind.json.JsonMapper
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** 검증용 probe endpoint 호출 도우미. */
class ProbeClient(port: Int, json: JsonMapper) {
    private val api = ApiClient(port, json)

    fun post(
        roomId: UUID,
        body: Map<String, Any?>,
        staffId: UUID?,
        idempotencyKey: String? = UUID.randomUUID().toString(),
        correlationId: String? = null,
    ): ApiClient.Response = api.post("/api/v1/test/rooms/$roomId/probe", body, staffId, idempotencyKey, correlationId)
}

/** 테스트마다 독립된 property를 만들어 서로 간섭하지 않게 한다 (테스트는 commit된 데이터를 사용). */
class ProbeWorld(properties: PropertyRepository, rooms: RoomRepository, staffs: StaffRepository) {
    val property: Property = properties.save(
        Property("Probe Hotel ${UUID.randomUUID()}", ZoneId.of("Asia/Seoul"), LocalTime.of(15, 0), LocalTime.of(11, 0), 30),
    )
    val room: Room = rooms.save(Room(property.getId(), "101", "STANDARD", 1, RoomOperationalStatus.VACANT_DIRTY))
    val manager: Staff = staffs.save(Staff(property.getId(), "MGR-01", StaffRole.MANAGER))
    val inactive: Staff = staffs.save(Staff(property.getId(), "HK-OFF", StaffRole.HOUSEKEEPER, active = false))
}
