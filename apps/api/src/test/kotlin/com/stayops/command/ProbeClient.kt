package com.stayops.command

import com.stayops.property.domain.Property
import com.stayops.property.infrastructure.PropertyRepository
import com.stayops.room.domain.Room
import com.stayops.room.domain.RoomOperationalStatus
import com.stayops.room.infrastructure.RoomRepository
import com.stayops.staff.domain.Staff
import com.stayops.staff.domain.StaffRole
import com.stayops.staff.infrastructure.StaffRepository
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** 실제 HTTP로 command를 호출하는 테스트 도우미. 동시성 테스트를 위해 MockMvc 대신 사용한다. */
class ProbeClient(private val port: Int, private val json: JsonMapper) {

    private val http = HttpClient.newHttpClient()

    data class Response(val status: Int, val headers: Map<String, List<String>>, val body: JsonNode) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        val code: String? get() = body.get("code")?.asString()
        val contentType: String? get() = header("Content-Type")
    }

    fun post(
        roomId: UUID,
        body: Map<String, Any?>,
        staffId: UUID?,
        idempotencyKey: String? = UUID.randomUUID().toString(),
        correlationId: String? = null,
    ): Response {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/v1/test/rooms/$roomId/probe"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        staffId?.let { builder.header("X-Demo-Staff-Id", it.toString()) }
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        correlationId?.let { builder.header("X-Correlation-Id", it) }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val parsed = if (response.body().isBlank()) json.createObjectNode() else json.readTree(response.body())
        return Response(response.statusCode(), response.headers().map(), parsed)
    }
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
