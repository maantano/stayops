package com.stayops.shared.error

/**
 * DB 제약 위반을 API 오류 코드로 변환한다 (docs/schema/SCHEMA.md).
 *
 * 동시 요청 경쟁에서 정상적으로 발생할 수 있는 제약만 4xx로 매핑한다. 그 외 CHECK 제약 위반은
 * 서비스 검증이 놓친 버그이므로 [ErrorCode.INTERNAL_ERROR]로 처리하고 서버 로그로 추적한다.
 */
object ConstraintErrorMapping {

    private val codes: Map<String, ErrorCode> = mapOf(
        "reservation_no_overlap" to ErrorCode.SCHEDULE_CONFLICT,
        "reservation_one_checked_in_per_room" to ErrorCode.INVALID_STATE,
        "staff_shift_no_overlap" to ErrorCode.SHIFT_CONFLICT,
        "task_one_active_workflow_per_room" to ErrorCode.ACTIVE_WORKFLOW_EXISTS,
        "task_one_checkout_cleaning_per_reservation" to ErrorCode.INVALID_STATE,
        "assignment_one_active_per_task" to ErrorCode.INVALID_STATE,
        "inspection_record_task_id_key" to ErrorCode.INVALID_STATE,
        "room_number_unique_per_property" to ErrorCode.INVALID_STATE,
    )

    private val quotedConstraint = Regex("""constraint "([^"]+)"""")

    data class Mapped(val code: ErrorCode, val constraint: String?)

    fun map(ex: Throwable): Mapped {
        val constraint = constraintName(ex)
        return Mapped(constraint?.let { codes[it] } ?: ErrorCode.INTERNAL_ERROR, constraint)
    }

    fun constraintName(ex: Throwable): String? {
        for (cause in generateSequence(ex) { it.cause }) {
            if (cause is org.hibernate.exception.ConstraintViolationException) {
                cause.constraintName?.let { return it.lowercase() }
            }
        }
        // 일부 제약(예: exclusion)은 Hibernate가 이름을 추출하지 못하므로 PostgreSQL 메시지에서 찾는다.
        for (cause in generateSequence(ex) { it.cause }) {
            cause.message?.let { message -> quotedConstraint.find(message)?.let { return it.groupValues[1] } }
        }
        return null
    }
}
