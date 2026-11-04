package com.stayops.support

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 실제 HTTP로 API를 호출하는 테스트 도우미. 동시성 테스트를 위해 MockMvc 대신 사용한다. */
class ApiClient(private val port: Int, private val json: JsonMapper) {

    private val http = HttpClient.newHttpClient()

    data class Response(val status: Int, val headers: Map<String, List<String>>, val body: JsonNode) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        val code: String? get() = body.get("code")?.asString()
        val contentType: String? get() = header("Content-Type")
        fun text(field: String): String? = body.get(field)?.takeUnless { it.isNull }?.asString()
    }

    fun post(
        path: String,
        body: Any? = null,
        staffId: UUID?,
        idempotencyKey: String? = UUID.randomUUID().toString(),
        correlationId: String? = null,
    ): Response {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody())
        staffId?.let { builder.header("X-Demo-Staff-Id", it.toString()) }
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        correlationId?.let { builder.header("X-Correlation-Id", it) }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val parsed = if (response.body().isBlank()) json.createObjectNode() else json.readTree(response.body())
        return Response(response.statusCode(), response.headers().map(), parsed)
    }

    companion object {
        fun assertProblem(response: Response, status: Int, code: String) {
            assertEquals(status, response.status, "body=${response.body}")
            assertEquals(code, response.code)
            assertTrue(response.contentType!!.startsWith("application/problem+json"), "content-type=${response.contentType}")
            assertNotNull(response.body.get("correlationId")?.asString(), "problem must carry correlationId")
            assertEquals("urn:stayops:problem:" + code.lowercase().replace('_', '-'), response.body.get("type").asString())
        }
    }
}
