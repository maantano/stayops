package com.stayops.shared.command

import com.stayops.shared.error.ErrorCode
import com.stayops.shared.error.StayOpsException
import com.stayops.shared.web.CorrelationIdFilter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID

/** `Idempotency-Key`는 모든 command에 필수다 (v4.1 02 §7, OPEN-6 결정). */
@Component
class CommandRequestArgumentResolver : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == CommandRequest::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): CommandRequest {
        val request = webRequest.getNativeRequest(HttpServletRequest::class.java)
            ?: error("CommandRequest requires a servlet request")
        val key = request.getHeader(IDEMPOTENCY_KEY_HEADER)?.trim()
        if (key.isNullOrEmpty()) {
            throw StayOpsException(ErrorCode.VALIDATION_ERROR, "$IDEMPOTENCY_KEY_HEADER header is required")
        }
        if (!KEY_PATTERN.matches(key)) {
            throw StayOpsException(
                ErrorCode.VALIDATION_ERROR,
                "$IDEMPOTENCY_KEY_HEADER must be 1-100 characters of letters, digits, '-', '_', ':', '.'",
            )
        }
        val pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
            ?: request.requestURI
        return CommandRequest(
            endpoint = "${request.method} $pattern",
            idempotencyKey = key,
            correlationId = CorrelationIdFilter.current(request) ?: UUID.randomUUID(),
        )
    }

    companion object {
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
        private val KEY_PATTERN = Regex("^[A-Za-z0-9_\\-:.]{1,100}$")
    }
}
