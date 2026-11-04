package com.stayops.shared.error

import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import kotlin.test.assertEquals

class ConstraintErrorMappingTests {

    private fun hibernateViolation(constraintName: String?, sqlMessage: String) =
        DataIntegrityViolationException(
            "could not execute statement",
            org.hibernate.exception.ConstraintViolationException("insert failed", SQLException(sqlMessage), constraintName),
        )

    @Test
    fun `uses the constraint name extracted by Hibernate`() {
        val ex = hibernateViolation("task_one_active_workflow_per_room", "duplicate key")
        assertEquals(ErrorCode.ACTIVE_WORKFLOW_EXISTS, ConstraintErrorMapping.map(ex).code)
    }

    @Test
    fun `falls back to the PostgreSQL message for exclusion constraints`() {
        val ex = hibernateViolation(
            null,
            """ERROR: conflicting key value violates exclusion constraint "reservation_no_overlap"""",
        )
        val mapped = ConstraintErrorMapping.map(ex)
        assertEquals(ErrorCode.SCHEDULE_CONFLICT, mapped.code)
        assertEquals("reservation_no_overlap", mapped.constraint)
    }

    @Test
    fun `maps race-prone constraints to their API codes`() {
        val expected = mapOf(
            "staff_shift_no_overlap" to ErrorCode.SHIFT_CONFLICT,
            "reservation_one_checked_in_per_room" to ErrorCode.INVALID_STATE,
            "assignment_one_active_per_task" to ErrorCode.INVALID_STATE,
            "inspection_record_task_id_key" to ErrorCode.INVALID_STATE,
            "task_one_checkout_cleaning_per_reservation" to ErrorCode.INVALID_STATE,
        )
        expected.forEach { (constraint, code) ->
            assertEquals(code, ConstraintErrorMapping.map(hibernateViolation(constraint, "x")).code, constraint)
        }
    }

    @Test
    fun `unmapped check constraint violation is an internal error`() {
        val ex = hibernateViolation("task_blocked_iff_status_before_blocked", "violates check constraint")
        assertEquals(ErrorCode.INTERNAL_ERROR, ConstraintErrorMapping.map(ex).code)
    }

    @Test
    fun `unknown violation without a constraint name is an internal error`() {
        assertEquals(ErrorCode.INTERNAL_ERROR, ConstraintErrorMapping.map(DataIntegrityViolationException("boom")).code)
    }
}
