package com.stayops.shared.web

import com.stayops.shared.error.ConstraintErrorMapping
import com.stayops.shared.error.ErrorCode
import com.stayops.shared.error.StateInconsistencyException
import com.stayops.shared.error.StayOpsException
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * 모든 오류를 RFC 9457 Problem Details로 응답한다 (v4.1 02 §7, 04 §9).
 * 공통 확장 필드: `code`, `correlationId`. 내부 stack trace·SQL·진단 정보는 응답에 넣지 않는다.
 */
@RestControllerAdvice
class ProblemDetailsHandler(meterRegistry: MeterRegistry) : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val stateInconsistentCounter = meterRegistry.counter("stayops.state.inconsistent")

    @ExceptionHandler(StayOpsException::class)
    fun handleStayOps(ex: StayOpsException, request: NativeWebRequest): ResponseEntity<ProblemDetail> {
        if (ex is StateInconsistencyException) {
            stateInconsistentCounter.increment()
            log.error("STATE_INCONSISTENT: {} diagnostics={}", ex.internalDetail, ex.diagnostics)
        }
        return problem(ex.code, ex.detail, request, ex.extensions)
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleIntegrity(ex: DataIntegrityViolationException, request: NativeWebRequest): ResponseEntity<ProblemDetail> {
        val mapped = ConstraintErrorMapping.map(ex)
        if (mapped.code == ErrorCode.INTERNAL_ERROR) {
            log.error("Unmapped DB constraint violation (constraint={})", mapped.constraint, ex)
        } else {
            log.info("DB constraint {} -> {}", mapped.constraint, mapped.code)
        }
        return problem(mapped.code, mapped.code.title, request)
    }

    /**
     * 낙관적 잠금 충돌, 잠금 대기 시간 초과(lock_timeout), deadlock (ADR-003 개정).
     * RoomLockScope는 EntityManager를 직접 쓰므로 Spring 예외 변환을 거치지 않은 JPA 예외도 함께 처리한다.
     */
    @ExceptionHandler(
        OptimisticLockingFailureException::class,
        PessimisticLockingFailureException::class,
        jakarta.persistence.OptimisticLockException::class,
        jakarta.persistence.PessimisticLockException::class,
        jakarta.persistence.LockTimeoutException::class,
    )
    fun handleLocking(ex: RuntimeException, request: NativeWebRequest): ResponseEntity<ProblemDetail> {
        log.info("Concurrent update conflict: {}", ex.javaClass.simpleName)
        return problem(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.title, request)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception, request: NativeWebRequest): ResponseEntity<ProblemDetail> {
        log.error("Unexpected error", ex)
        return problem(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.title, request)
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val body = ex.body
        body.setProperty(
            "fieldErrors",
            ex.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to (it.defaultMessage ?: "invalid")) },
        )
        return handleExceptionInternal(ex, body, headers, status, request)
    }

    /** Spring MVC 기본 예외(400/404/405/415 등)에도 `code`·`correlationId`를 붙인다. */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = (body as? ProblemDetail) ?: ProblemDetail.forStatus(statusCode)
        val code = codeForStatus(statusCode)
        problem.type = code.type
        problem.setProperty("code", code.name)
        problem.setProperty("correlationId", correlationId(request))
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request)
    }

    private fun codeForStatus(status: HttpStatusCode): ErrorCode = when (status.value()) {
        400, 422 -> ErrorCode.VALIDATION_ERROR
        401 -> ErrorCode.UNAUTHENTICATED
        403 -> ErrorCode.FORBIDDEN
        404 -> ErrorCode.NOT_FOUND
        405, 406, 415 -> ErrorCode.REQUEST_NOT_SUPPORTED
        409 -> ErrorCode.INVALID_STATE
        else -> ErrorCode.INTERNAL_ERROR
    }

    private fun problem(
        code: ErrorCode,
        detail: String,
        request: NativeWebRequest,
        extensions: Map<String, Any?> = emptyMap(),
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(code.status, detail)
        problem.type = code.type
        problem.title = code.title
        problem.setProperty("code", code.name)
        problem.setProperty("correlationId", correlationId(request))
        extensions.forEach { (key, value) -> problem.setProperty(key, value) }
        return ResponseEntity.status(code.status).body(problem)
    }

    private fun correlationId(request: WebRequest): String? =
        CorrelationIdFilter.current((request as? NativeWebRequest)?.getNativeRequest(HttpServletRequest::class.java))?.toString()
}
