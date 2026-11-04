package com.stayops.shared

import com.stayops.TestcontainersConfiguration
import com.stayops.support.ApiClient
import com.stayops.support.ApiClient.Companion.assertProblem
import com.stayops.support.StayWorld
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.assertEquals

/**
 * P12 보안 점검: 본문 크기 상한. 상한을 넘는 본문은 파싱 전에 413 PAYLOAD_TOO_LARGE로 거절한다.
 * 테스트에서는 상한을 32KB로 낮추고, 17KB짜리 언어 간 계약 데이터셋이 그대로 통과하는지도 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["stayops.web.max-request-size=32KB"])
@Import(TestcontainersConfiguration::class)
class RequestSizeLimitTests(
    @Autowired private val json: JsonMapper,
    @Autowired private val world: StayWorld,
    @LocalServerPort private val port: Int,
) {
    private lateinit var hotel: StayWorld.Hotel
    private val http = HttpClient.newHttpClient()
    private val fixture = javaClass.getResource("/simulation/dataset-contract.json")!!.readBytes()
    private val oversized = ("{\"padding\":\"" + "x".repeat(40 * 1024) + "\"}").toByteArray()

    @BeforeEach
    fun setUp() {
        hotel = world.newHotel()
    }

    private fun upload(publisher: HttpRequest.BodyPublisher): ApiClient.Response {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/v1/properties/${hotel.property.getId()}/simulation-datasets"))
            .header("Content-Type", "application/json")
            .header("X-Demo-Staff-Id", hotel.manager.getId().toString())
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .POST(publisher)
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return ApiClient.Response(response.statusCode(), response.headers().map(), json.readTree(response.body()))
    }

    /** `ofInputStream`은 길이를 모르는 chunked 본문으로 보낸다. */
    private fun chunked(bytes: ByteArray) = HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(bytes) }

    @Test
    fun `a body whose Content-Length exceeds the limit is rejected before parsing`() {
        val response = upload(HttpRequest.BodyPublishers.ofByteArray(oversized))
        assertProblem(response, 413, "PAYLOAD_TOO_LARGE")
        assertEquals(32L * 1024, response.body.get("maxBytes").asLong())
    }

    @Test
    fun `a chunked body is cut off once it passes the limit`() {
        assertProblem(upload(chunked(oversized)), 413, "PAYLOAD_TOO_LARGE")
    }

    @Test
    fun `bodies under the limit pass, with or without Content-Length`() {
        assertEquals(201, upload(HttpRequest.BodyPublishers.ofByteArray(fixture)).status)
        assertEquals(200, upload(chunked(fixture)).status) // 같은 내용 → 기존 데이터셋 반환
    }
}
