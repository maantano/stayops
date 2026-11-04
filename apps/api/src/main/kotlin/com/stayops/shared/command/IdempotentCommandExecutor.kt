package com.stayops.shared.command

import com.stayops.identity.Actor
import com.stayops.shared.error.ErrorCode
import com.stayops.shared.error.StayOpsException
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock

/**
 * command 실행 파이프라인 (v4.1 04 §3):
 * Idempotency-Key 확인 → transaction 시작 → key 단위 직렬화 → 재확인 → command 실행
 * (도메인 잠금·상태 변경·이벤트는 command 안에서) → 응답 저장 → commit.
 *
 * - 같은 key + 같은 요청: 최초 응답을 그대로 반환한다(이후 상태가 바뀌었어도). 헤더 `Idempotency-Replayed: true`.
 * - 같은 key + 다른 요청: 409 IDEMPOTENCY_CONFLICT.
 * - 같은 key로 동시에 들어온 요청: transaction 범위 advisory lock으로 직렬화되어, 두 번째 요청은
 *   첫 번째가 commit한 결과를 재사용한다. (도메인 잠금이 아니므로 ADR-003과 충돌하지 않는다.)
 * - 실패한 command는 전체 rollback되므로 저장되지 않는다. 재시도하면 최신 상태로 다시 평가된다.
 */
@Component
class IdempotentCommandExecutor(
    private val records: IdempotencyRecordRepository,
    transactionManager: PlatformTransactionManager,
    private val jdbc: JdbcTemplate,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val transaction = TransactionTemplate(transactionManager)
    private val replayCounter = meterRegistry.counter("stayops.idempotency.replay")

    fun <T : Any> execute(
        request: CommandRequest,
        actor: Actor,
        payload: Any,
        responseType: Class<T>,
        command: (CommandContext) -> CommandResult<T>,
    ): CommandOutcome<T> {
        val requestHash = hash(request.endpoint + "\n" + jsonMapper.writeValueAsString(payload))

        findRecord(actor, request)?.let { return replay(it, requestHash, responseType) }

        return transaction.execute {
            jdbc.query("SELECT pg_advisory_xact_lock(?)", { _ -> }, lockKey(actor, request))
            findRecord(actor, request)?.let { return@execute replay(it, requestHash, responseType) }

            val context = CommandContext(actor, request.correlationId, request.idempotencyKey, clock.instant())
            val result = command(context)
            records.save(
                IdempotencyRecord(
                    actorId = actor.staffId,
                    endpoint = request.endpoint,
                    idempotencyKey = request.idempotencyKey,
                    requestHash = requestHash,
                    responseStatus = result.status.value(),
                    responseBodyJson = jsonMapper.writeValueAsString(result.body),
                    correlationId = request.correlationId,
                    createdAt = context.now,
                ),
            )
            CommandOutcome(result.status, result.body, replayed = false)
        }!!
    }

    private fun findRecord(actor: Actor, request: CommandRequest): IdempotencyRecord? =
        records.findByActorIdAndEndpointAndIdempotencyKey(actor.staffId, request.endpoint, request.idempotencyKey)

    private fun <T : Any> replay(record: IdempotencyRecord, requestHash: String, responseType: Class<T>): CommandOutcome<T> {
        if (record.requestHash != requestHash) {
            throw StayOpsException(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                "Idempotency-Key was already used with a different request",
            )
        }
        replayCounter.increment()
        return CommandOutcome(
            status = HttpStatus.valueOf(record.responseStatus),
            body = jsonMapper.readValue(record.responseBodyJson, responseType),
            replayed = true,
        )
    }

    private fun lockKey(actor: Actor, request: CommandRequest): Long =
        ByteBuffer.wrap(sha256("${actor.staffId}|${request.endpoint}|${request.idempotencyKey}")).long

    private fun hash(value: String): String = sha256(value).joinToString("") { "%02x".format(it) }

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
}
