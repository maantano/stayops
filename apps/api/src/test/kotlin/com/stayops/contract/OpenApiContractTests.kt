package com.stayops.contract

import com.stayops.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * API 계약 테스트 (ADR-006, docs/v4.1/05 §2 Contract).
 * 서버가 생성한 OpenAPI가 저장소의 `docs/api/openapi.json`과 같아야 한다. FE 타입은 이 파일에서 생성한다.
 * API를 바꿨다면 `STAYOPS_WRITE_OPENAPI=true ./gradlew test --tests '*OpenApiContractTests*'`로 갱신하고
 * `apps/web`에서 `pnpm api:types`로 타입을 다시 만든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class OpenApiContractTests(
    @Autowired private val json: JsonMapper,
    @LocalServerPort private val port: Int,
) {
    private val snapshot = File("../../docs/api/openapi.json")

    private fun generated(): JsonNode {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/v3/api-docs")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        return json.readTree(response.body())
    }

    private fun canonical(node: JsonNode): String =
        json.rebuild().enable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()
            .writeValueAsString(json.treeToValue(node, Map::class.java)) + "\n"

    @Test
    fun `generated OpenAPI matches the committed contract`() {
        val current = canonical(generated())
        if (System.getenv("STAYOPS_WRITE_OPENAPI") == "true") {
            snapshot.parentFile.mkdirs()
            snapshot.writeText(current)
            println("[contract] wrote ${snapshot.canonicalPath}")
            return
        }
        if (!snapshot.exists()) fail("missing ${snapshot.canonicalPath}; run with STAYOPS_WRITE_OPENAPI=true")
        assertEquals(snapshot.readText(), current, "OpenAPI changed. Regenerate docs/api/openapi.json and FE types (see class doc).")
    }

    @Test
    fun `every command requires an Idempotency-Key and every endpoint documents the demo actor header`() {
        val paths = generated().get("paths")
        val problems = mutableListOf<String>()
        for (path in paths.propertyNames()) {
            if (!path.startsWith("/api/v1/") || path.startsWith("/api/v1/demo/")) continue // 데모 사용자 목록은 인증 전 화면용
            val item = paths.get(path)
            for (method in item.propertyNames()) {
                val op = item.get(method)
                val headers = (0 until (op.get("parameters")?.size() ?: 0)).map { op.get("parameters").get(it) }
                    .filter { it.get("in").asString() == "header" }.associate { it.get("name").asString() to it.get("required")?.asBoolean() }
                if (headers["X-Demo-Staff-Id"] != true) problems += "$method $path: X-Demo-Staff-Id not required"
                if (method in setOf("post", "delete") && headers["Idempotency-Key"] != true) problems += "$method $path: Idempotency-Key not required"
                val queryNames = (0 until (op.get("parameters")?.size() ?: 0)).map { op.get("parameters").get(it) }
                    .filter { it.get("in").asString() == "query" }.map { it.get("name").asString() }
                if (queryNames.any { it == "actor" || it == "command" }) problems += "$method $path: internal argument leaked as query param"
                if (op.get("responses")?.get("409") == null && method in setOf("post", "delete")) problems += "$method $path: 409 not documented"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        assertTrue(generated().get("components").get("schemas").has("Problem"))
    }
}
