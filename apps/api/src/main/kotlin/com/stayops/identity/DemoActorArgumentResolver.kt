package com.stayops.identity

import com.stayops.shared.error.ErrorCode
import com.stayops.shared.error.StayOpsException
import com.stayops.staff.infrastructure.StaffRepository
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.util.UUID

/**
 * 데모 인증 (ADR-005): `X-Demo-Staff-Id` 헤더의 활성 Staff를 요청자로 본다.
 * 합성 데이터 데모 전용이며 실제 인증이 아니다. 운영 배포 전 교체해야 한다.
 */
@Component
class DemoActorArgumentResolver(private val staffRepository: StaffRepository) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean = parameter.parameterType == Actor::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Actor {
        val raw = webRequest.getHeader(HEADER)
            ?: throw StayOpsException(ErrorCode.UNAUTHENTICATED, "$HEADER header is required")
        val staffId = runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw StayOpsException(ErrorCode.UNAUTHENTICATED, "$HEADER must be a UUID")
        val staff = staffRepository.findById(staffId).orElse(null)
        if (staff == null || !staff.active) {
            throw StayOpsException(ErrorCode.UNAUTHENTICATED, "unknown or inactive staff")
        }
        return Actor(staffId = staff.getId(), propertyId = staff.propertyId, role = staff.role)
    }

    companion object {
        const val HEADER = "X-Demo-Staff-Id"
    }
}
