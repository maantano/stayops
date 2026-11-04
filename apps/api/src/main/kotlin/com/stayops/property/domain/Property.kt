package com.stayops.property.domain

import com.stayops.shared.domain.AuditedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

@Entity
@Table(name = "property")
class Property(
    name: String,
    timezone: ZoneId,
    defaultCheckInTime: LocalTime,
    defaultCheckOutTime: LocalTime,
    operationalBufferMinutes: Int,
    id: UUID = UUID.randomUUID(),
) : AuditedEntity(id) {

    @Column(name = "name", nullable = false, length = 100)
    var name: String = name
        protected set

    /** IANA zone id. 저장은 문자열, 사용은 [zoneId]. */
    @Column(name = "timezone", nullable = false, length = 64)
    var timezone: String = timezone.id
        protected set

    @Column(name = "default_check_in_time", nullable = false)
    var defaultCheckInTime: LocalTime = defaultCheckInTime
        protected set

    @Column(name = "default_check_out_time", nullable = false)
    var defaultCheckOutTime: LocalTime = defaultCheckOutTime
        protected set

    @Column(name = "operational_buffer_minutes", nullable = false)
    var operationalBufferMinutes: Int = operationalBufferMinutes
        protected set

    val zoneId: ZoneId
        get() = ZoneId.of(timezone)

    init {
        require(operationalBufferMinutes >= 0) { "operationalBufferMinutes must be >= 0" }
    }
}
