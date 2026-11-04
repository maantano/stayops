package com.stayops.simulation.infrastructure

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

/** 업로드된 합성 데이터셋. 불변 입력 스냅샷이다 (02 §9). */
@Entity
@Immutable
@Table(name = "simulation_dataset")
class SimulationDatasetEntity(
    propertyId: UUID,
    seed: Long,
    generatorVersion: String,
    configHash: String,
    contentHash: String,
    summaryJson: String,
    payloadJson: String,
    createdBy: UUID,
    createdAt: Instant,
    id: UUID = UUID.randomUUID(),
) : BaseEntity(id) {
    @Column(name = "property_id", nullable = false) val propertyId: UUID = propertyId
    @Column(name = "seed", nullable = false) val seed: Long = seed
    @Column(name = "generator_version", nullable = false) val generatorVersion: String = generatorVersion
    @Column(name = "config_hash", nullable = false) val configHash: String = configHash
    @Column(name = "content_hash", nullable = false) val contentHash: String = contentHash

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary", nullable = false, columnDefinition = "jsonb")
    val summaryJson: String = summaryJson

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    val payloadJson: String = payloadJson

    @Column(name = "created_by", nullable = false) val createdBy: UUID = createdBy
    @Column(name = "created_at", nullable = false) val createdAt: Instant = createdAt
}

/** 정책 비교 실행 결과 (02 §2 SimulationRun). MVP는 요청 안에서 동기 실행하고 완료된 결과만 저장한다. */
@Entity
@Immutable
@Table(name = "simulation_run")
class SimulationRunEntity(
    propertyId: UUID,
    datasetId: UUID,
    seed: Long,
    engineVersion: String,
    policyVersion: String,
    scenariosJson: String,
    resultsJson: String,
    status: String,
    createdBy: UUID,
    startedAt: Instant,
    completedAt: Instant?,
    createdAt: Instant,
    id: UUID = UUID.randomUUID(),
) : BaseEntity(id) {
    @Column(name = "property_id", nullable = false) val propertyId: UUID = propertyId
    @Column(name = "dataset_id", nullable = false) val datasetId: UUID = datasetId
    @Column(name = "seed", nullable = false) val seed: Long = seed
    @Column(name = "engine_version", nullable = false) val engineVersion: String = engineVersion
    @Column(name = "policy_version", nullable = false) val policyVersion: String = policyVersion

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scenarios", nullable = false, columnDefinition = "jsonb")
    val scenariosJson: String = scenariosJson

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "results", nullable = false, columnDefinition = "jsonb")
    val resultsJson: String = resultsJson

    @Column(name = "status", nullable = false) val status: String = status
    @Column(name = "created_by", nullable = false) val createdBy: UUID = createdBy
    @Column(name = "started_at", nullable = false) val startedAt: Instant = startedAt
    @Column(name = "completed_at") val completedAt: Instant? = completedAt
    @Column(name = "created_at", nullable = false) val createdAt: Instant = createdAt
}

interface SimulationDatasetRepository : JpaRepository<SimulationDatasetEntity, UUID> {
    fun findByPropertyIdAndContentHash(propertyId: UUID, contentHash: String): SimulationDatasetEntity?

    fun findTop50ByPropertyIdOrderByCreatedAtDesc(propertyId: UUID): List<SimulationDatasetEntity>
}

interface SimulationRunRepository : JpaRepository<SimulationRunEntity, UUID> {
    fun findTop50ByPropertyIdOrderByCreatedAtDesc(propertyId: UUID): List<SimulationRunEntity>
}
