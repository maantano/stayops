package com.stayops.shared.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * 요청마다 correlation ID를 정하고 MDC·요청 속성·응답 헤더에 싣는다 (v4.1 02 §7).
 * 클라이언트가 유효한 UUID를 보내면 그대로 쓰고, 없거나 형식이 틀리면 새로 만든다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val correlationId = request.getHeader(HEADER)?.let(::parseUuid) ?: UUID.randomUUID()
        request.setAttribute(ATTRIBUTE, correlationId)
        response.setHeader(HEADER, correlationId.toString())
        MDC.put(MDC_KEY, correlationId.toString())
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    private fun parseUuid(value: String): UUID? = runCatching { UUID.fromString(value.trim()) }.getOrNull()

    companion object {
        const val HEADER = "X-Correlation-Id"
        const val ATTRIBUTE = "stayops.correlationId"
        const val MDC_KEY = "correlationId"

        fun current(request: HttpServletRequest?): UUID? =
            request?.getAttribute(ATTRIBUTE) as? UUID ?: MDC.get(MDC_KEY)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }
}
