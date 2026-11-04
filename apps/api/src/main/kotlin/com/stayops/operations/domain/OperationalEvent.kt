package com.stayops.operations.domain

import com.stayops.shared.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

enum class ActorType {
    STAFF,
    SYSTEM,
}

/**
 * 업무 감사 이벤트 (append-only). 도메인 테이블의 대체물이 아니다.
 * 상태 변경과 같은 transaction에서 기록한다 (기록 서비스는 P2.5).
 */
@Entity
@Immutable
@Table(name = "operational_event")
class OperationalEvent(
    propertyId: UUID,
    aggregateType: String,
    aggregateId: UUID,
    eventType: String,
    aggregateVersion: Long,
    actorType: ActorType,
    actorId: UUID?,
    correlationId: UUID,
    payloadSchemaVersion: Int,
    payloadJson: String,
    occurredAt: Instant,
    idempotencyKey: String? = null,
    id: UUID = UUID.randomUUID(),
) : BaseEntity(id) {

    @Column(name = "property_id", nullable = false, updatable = false)
    val propertyId: UUID = propertyId

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 40)
    val aggregateType: String = aggregateType

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    val aggregateId: UUID = aggregateId

    @Column(name = "event_type", nullable = false, updatable = false, length = 60)
    val eventType: String = eventType

    @Column(name = "aggregate_version", nullable = false, updatable = false)
    val aggregateVersion: Long = aggregateVersion

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false, length = 20)
    val actorType: ActorType = actorType

    @Column(name = "actor_id", updatable = false)
    val actorId: UUID? = actorId

    @Column(name = "correlation_id", nullable = false, updatable = false)
    val correlationId: UUID = correlationId

    @Column(name = "idempotency_key", updatable = false, length = 100)
    val idempotencyKey: String? = idempotencyKey

    @Column(name = "payload_schema_version", nullable = false, updatable = false)
    val payloadSchemaVersion: Int = payloadSchemaVersion

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    val payloadJson: String = payloadJson

    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant = occurredAt
}
