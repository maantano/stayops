package com.stayops.shared.domain

import jakarta.persistence.Column
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Transient
import jakarta.persistence.Version
import org.hibernate.Hibernate
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

/**
 * 애플리케이션이 UUID를 할당하는 엔티티의 공통 부모.
 *
 * id를 생성 시점에 할당하므로(이벤트·후속 task가 저장 전에 id를 참조해야 함) Spring Data가
 * 신규 여부를 id로 판단할 수 없다. [Persistable]로 신규 여부를 직접 알려 `persist`가 호출되게 한다.
 */
@MappedSuperclass
abstract class BaseEntity(id: UUID) : Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val id: UUID = id

    @Transient
    private var newEntity: Boolean = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = newEntity

    @PostPersist
    @PostLoad
    protected fun markNotNew() {
        newEntity = false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BaseEntity) return false
        if (Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return id == other.getId()
    }

    override fun hashCode(): Int = id.hashCode()
}

/** 생성·수정 시각과 낙관적 잠금 version을 가진 aggregate 엔티티. */
@MappedSuperclass
abstract class AuditedEntity(id: UUID) : BaseEntity(id) {

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set
}
