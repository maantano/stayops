package com.stayops.shared.web

import com.stayops.shared.error.ErrorCode
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper
import java.io.IOException

/**
 * 요청 본문 크기 상한 (P12 보안 점검). JSON 본문은 컨트롤러 권한 확인 전에 메모리로 파싱되므로,
 * 인증과 무관하게 큰 본문으로 메모리를 소모시키는 요청을 파싱 전에 413 `PAYLOAD_TOO_LARGE`로 거절한다.
 *
 * - `Content-Length`가 상한을 넘으면 본문을 읽지 않고 거절한다.
 * - 길이를 알 수 없는 chunked 본문은 읽은 바이트를 세다가 상한을 넘는 순간 읽기를 중단한다.
 * 기본 상한 5MB는 기본 설정 합성 데이터셋(80실·14일, 약 450KB)의 10배 이상이다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RequestSizeLimitFilter(
    @Value("\${stayops.web.max-request-size:5MB}") maxRequestSize: DataSize,
    private val jsonMapper: JsonMapper,
) : OncePerRequestFilter() {

    private val limit = maxRequestSize.toBytes()

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val declared = request.contentLengthLong
        if (declared > limit) {
            reject(request, response)
            return
        }
        val wrapped = if (declared < 0) CountingRequest(request, limit) else request
        try {
            chain.doFilter(wrapped, response)
        } catch (ex: Exception) {
            if ((wrapped as? CountingRequest)?.exceeded == true && !response.isCommitted) {
                response.reset()
                reject(request, response)
            } else {
                throw ex
            }
        }
    }

    private fun reject(request: HttpServletRequest, response: HttpServletResponse) {
        val code = ErrorCode.PAYLOAD_TOO_LARGE
        val problem = ProblemDetail.forStatusAndDetail(code.status, "request body exceeds ${limit / 1024} KB")
        problem.type = code.type
        problem.title = code.title
        problem.setProperty("code", code.name)
        problem.setProperty("correlationId", CorrelationIdFilter.current(request)?.toString())
        problem.setProperty("maxBytes", limit)
        response.status = code.status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = "UTF-8"
        CorrelationIdFilter.current(request)?.let { response.setHeader(CorrelationIdFilter.HEADER, it.toString()) }
        response.writer.write(jsonMapper.writeValueAsString(problem))
    }

    private class CountingRequest(request: HttpServletRequest, private val limit: Long) : HttpServletRequestWrapper(request) {
        var exceeded = false
            private set
        private var stream: ServletInputStream? = null

        override fun getInputStream(): ServletInputStream {
            stream?.let { return it }
            val delegate = super.getInputStream()
            return object : ServletInputStream() {
                private var count = 0L

                override fun read(): Int {
                    val b = delegate.read()
                    if (b >= 0) count(1)
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = delegate.read(b, off, len)
                    if (n > 0) count(n.toLong())
                    return n
                }

                private fun count(n: Long) {
                    count += n
                    if (count > limit) {
                        exceeded = true
                        throw RequestTooLargeException(limit)
                    }
                }

                override fun isFinished() = delegate.isFinished
                override fun isReady() = delegate.isReady
                override fun setReadListener(listener: ReadListener) = delegate.setReadListener(listener)
            }.also { stream = it }
        }
    }
}

/** chunked 본문이 상한을 넘었을 때 읽기를 멈추는 예외. Spring이 감싸서 던지면 [ProblemDetailsHandler]가 413으로 바꾼다. */
class RequestTooLargeException(val maxBytes: Long) : IOException("request body exceeds $maxBytes bytes")
