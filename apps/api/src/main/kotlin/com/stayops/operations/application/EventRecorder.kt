package com.stayops.operations.application

import com.stayops.operations.domain.ActorType
import com.stayops.operations.domain.OperationalEvent
import com.stayops.operations.infrastructure.OperationalEventRepository
import com.stayops.shared.command.CommandContext
import com.stayops.shared.domain.AuditedEntity
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * 상태 변경과 같은 transaction에서 업무 이벤트를 기록한다 (v4.1 02 §1-4, 04 §7).
 * transaction 밖에서 호출하면 실패한다 (MANDATORY).
 */
@Component
class EventRecorder(
    private val events: OperationalEventRepository,
    private val entityManager: EntityManager,
    private val jsonMapper: JsonMapper,
) {

    /**
     * [aggregate]의 변경을 먼저 flush해 version을 확정한 뒤 그 값을 aggregateVersion으로 기록한다.
     * 같은 command에서 같은 aggregate에 이벤트를 여러 건 남기면 같은 version을 공유한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(
        context: CommandContext,
        propertyId: UUID,
        aggregateType: String,
        aggregate: AuditedEntity,
        eventType: String,
        payload: Any,
        payloadSchemaVersion: Int = 1,
    ): OperationalEvent {
        entityManager.flush()
        return events.save(
            OperationalEvent(
                propertyId = propertyId,
                aggregateType = aggregateType,
                aggregateId = aggregate.getId(),
                eventType = eventType,
                aggregateVersion = aggregate.version,
                actorType = ActorType.STAFF,
                actorId = context.actor.staffId,
                correlationId = context.correlationId,
                idempotencyKey = context.idempotencyKey,
                payloadSchemaVersion = payloadSchemaVersion,
                payloadJson = jsonMapper.writeValueAsString(payload),
                occurredAt = context.now,
            ),
        )
    }
}
