package com.stayops.shared.error

import org.springframework.http.HttpStatus
import java.net.URI

/**
 * API 오류 코드 단일 목록 (docs/v4.1/02 §7.1).
 * 이 enum이 코드 상의 기준이며, 문서 표와 함께 갱신한다.
 */
enum class ErrorCode(val status: HttpStatus, val title: String) {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "요청자를 식별할 수 없습니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다"),
    REQUEST_NOT_SUPPORTED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 형식입니다"),
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서 수행할 수 없는 요청입니다"),
    ROOM_NOT_AVAILABLE(HttpStatus.CONFLICT, "사용할 수 없는 객실입니다"),
    ACTIVE_BLOCKER_EXISTS(HttpStatus.CONFLICT, "해제되지 않은 차단 사유가 있습니다"),
    ASSIGNMENT_INVALID(HttpStatus.CONFLICT, "담당자 배정이 유효하지 않습니다"),
    SHIFT_CONFLICT(HttpStatus.CONFLICT, "근무시간과 맞지 않습니다"),
    SCHEDULE_CONFLICT(HttpStatus.CONFLICT, "예약 시간이 겹칩니다"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 변경과 충돌했습니다. 최신 상태를 확인한 뒤 다시 시도하세요"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "같은 Idempotency-Key가 다른 요청에 이미 사용되었습니다"),
    ACTIVE_WORKFLOW_EXISTS(HttpStatus.CONFLICT, "객실에 진행 중인 작업이 이미 있습니다"),
    STAYOVER_IN_PROGRESS(HttpStatus.CONFLICT, "투숙 중 청소가 진행 중이라 체크아웃할 수 없습니다"),
    STATE_INCONSISTENT(HttpStatus.CONFLICT, "저장된 상태가 서로 맞지 않아 요청을 처리하지 않았습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다"),
    ;

    val type: URI = URI.create("urn:stayops:problem:" + name.lowercase().replace('_', '-'))
}

/**
 * 도메인·애플리케이션 오류. [extensions]는 Problem Details에 그대로 노출되므로
 * 클라이언트에게 보여줘도 되는 값만 넣는다.
 */
open class StayOpsException(
    val code: ErrorCode,
    val detail: String,
    val extensions: Map<String, Any?> = emptyMap(),
) : RuntimeException("${code.name}: $detail")

class NotFoundException(resource: String, id: Any?) :
    StayOpsException(ErrorCode.NOT_FOUND, "$resource not found: $id")

/**
 * 저장된 상태끼리 모순될 때 사용한다 (v4.1 02 §1-8). 임의로 보정하지 않는다.
 * [diagnostics]는 서버 로그에만 남기고 응답에는 노출하지 않는다.
 */
class StateInconsistencyException(
    detail: String,
    val diagnostics: Map<String, Any?>,
) : StayOpsException(ErrorCode.STATE_INCONSISTENT, ErrorCode.STATE_INCONSISTENT.title) {
    val internalDetail: String = detail
}
