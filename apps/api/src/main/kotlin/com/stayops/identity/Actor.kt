package com.stayops.identity

import com.stayops.shared.error.ErrorCode
import com.stayops.shared.error.StayOpsException
import com.stayops.staff.domain.StaffRole
import java.util.UUID

/**
 * 요청자. 데모 인증(ADR-005)에서는 활성 Staff 한 명이 곧 요청자다.
 * 실제 인증 방식이 정해지면 이 타입은 유지하고 해석기만 교체한다.
 */
data class Actor(
    val staffId: UUID,
    val propertyId: UUID,
    val role: StaffRole,
) {
    /** 다른 property의 리소스는 존재 여부를 숨기기 위해 404로 응답한다 (v4.1 02 §7.1). */
    fun requireProperty(propertyId: UUID) {
        if (propertyId != this.propertyId) {
            throw StayOpsException(ErrorCode.NOT_FOUND, "property not found: $propertyId")
        }
    }

    fun requireRole(vararg roles: StaffRole) {
        if (role !in roles) {
            throw StayOpsException(ErrorCode.FORBIDDEN, "role $role is not allowed for this action")
        }
    }
}
