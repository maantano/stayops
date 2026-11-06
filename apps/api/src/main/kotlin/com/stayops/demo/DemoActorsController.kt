package com.stayops.demo

import com.stayops.property.infrastructure.PropertyRepository
import com.stayops.staff.domain.StaffRole
import com.stayops.staff.infrastructure.StaffRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 데모 사용자 선택 화면용 목록 (ADR-005). `stayops.demo-mode=true`일 때만 등록된다.
 * 인증 없이 직원 id를 노출하므로 데모 데이터 환경 전용이다 — 운영 배포에서는 demo-mode를 끈다.
 */
@RestController
@ConditionalOnProperty(name = ["stayops.demo-mode"], havingValue = "true")
class DemoActorsController(
    private val properties: PropertyRepository,
    private val staff: StaffRepository,
) {
    data class DemoProperty(val propertyId: UUID, val name: String, val timezone: String, val actors: List<DemoActor>)

    data class DemoActor(val staffId: UUID, val displayName: String, val role: StaffRole)

    @GetMapping("/api/v1/demo/actors")
    @Transactional(readOnly = true)
    fun actors(): List<DemoProperty> =
        properties.findAll().sortedBy { it.name }.map { property ->
            DemoProperty(
                propertyId = property.getId(),
                name = property.name,
                timezone = property.timezone,
                actors = staff.findByPropertyIdAndActiveTrue(property.getId())
                    .sortedWith(compareBy({ ROLE_ORDER.indexOf(it.role) }, { it.displayName }))
                    .map { DemoActor(it.getId(), it.displayName, it.role) },
            )
        }

    companion object {
        private val ROLE_ORDER = listOf(StaffRole.MANAGER, StaffRole.FRONT_DESK, StaffRole.HOUSEKEEPER, StaffRole.INSPECTOR)
    }
}
