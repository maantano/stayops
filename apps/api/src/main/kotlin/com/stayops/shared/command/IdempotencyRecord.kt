package com.stayops.shared.command

import com.stayops.shared.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/** 성공한 command의 최초 응답. 범위는 actor + endpoint + key (v4.1 02 §7). */
@Entity
@Immutable
@Table(name = "idempotency_record")
class IdempotencyRecord(
    actorId: UUID,
    endpoint: String,
    idempotencyKey: String,
    requestHash: String,
    responseStatus: Int,
    responseBodyJson: String,
    correlationId: UUID,
    createdAt: Instant,
    id: UUID = UUID.randomUUID(),
) : BaseEntity(id) {

    @Column(name = "actor_id", nullable = false, updatable = false)
    val actorId: UUID = actorId

    @Column(name = "endpoint", nullable = false, updatable = false, length = 200)
    val endpoint: String = endpoint

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    val idempotencyKey: String = idempotencyKey

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    val requestHash: String = requestHash

    @Column(name = "response_status", nullable = false, updatable = false)
    val responseStatus: Int = responseStatus

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", nullable = false, updatable = false, columnDefinition = "jsonb")
    val responseBodyJson: String = responseBodyJson

    @Column(name = "correlation_id", nullable = false, updatable = false)
    val correlationId: UUID = correlationId

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = createdAt
}

interface IdempotencyRecordRepository : JpaRepository<IdempotencyRecord, UUID> {
    fun findByActorIdAndEndpointAndIdempotencyKey(actorId: UUID, endpoint: String, idempotencyKey: String): IdempotencyRecord?
}
