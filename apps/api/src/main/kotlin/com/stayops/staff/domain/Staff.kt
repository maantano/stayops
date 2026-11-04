package com.stayops.staff.domain

import com.stayops.shared.domain.AuditedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** 02 §2 Staff. MANAGER·FRONT_DESK는 task 담당자가 될 수 없다(배정 규칙은 P4에서 강제). */
enum class StaffRole {
    FRONT_DESK,
    HOUSEKEEPER,
    INSPECTOR,
    MANAGER,
}

@Entity
@Table(name = "staff")
class Staff(
    propertyId: UUID,
    displayName: String,
    role: StaffRole,
    active: Boolean = true,
    skills: List<String> = emptyList(),
    id: UUID = UUID.randomUUID(),
) : AuditedEntity(id) {

    @Column(name = "property_id", nullable = false, updatable = false)
    val propertyId: UUID = propertyId

    /** 내부 식별용 표시명. 실명 등 개인정보를 넣지 않는다 (예: "HK-03"). */
    @Column(name = "display_name", nullable = false, length = 50)
    var displayName: String = displayName
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    var role: StaffRole = role
        protected set

    @Column(name = "active", nullable = false)
    var active: Boolean = active
        protected set

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "skills", nullable = false, columnDefinition = "text[]")
    var skills: List<String> = skills
        protected set
}

@Entity
@Table(name = "staff_shift")
class StaffShift(
    staffId: UUID,
    startsAt: Instant,
    endsAt: Instant,
    breakMinutes: Int = 0,
    id: UUID = UUID.randomUUID(),
) : AuditedEntity(id) {

    @Column(name = "staff_id", nullable = false, updatable = false)
    val staffId: UUID = staffId

    @Column(name = "starts_at", nullable = false)
    var startsAt: Instant = startsAt
        protected set

    @Column(name = "ends_at", nullable = false)
    var endsAt: Instant = endsAt
        protected set

    @Column(name = "break_minutes", nullable = false)
    var breakMinutes: Int = breakMinutes
        protected set
}
