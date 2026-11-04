package com.stayops.shared.command

import com.stayops.identity.Actor
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * command 하나의 실행 문맥. 서비스에 명시적으로 전달한다.
 *
 * [now]는 **처음 읽는 순간** 한 번 정해지고 이후 고정된다. 서비스는 객실 잠금(ADR-003)을 잡은 뒤에 시각을 읽으므로,
 * 잠금으로 직렬화된 실행 순서와 기록 시각의 순서가 일치한다. command 시작(잠금 대기 전) 시각을 쓰면 늦게 잠금을 얻은
 * command가 앞선 command보다 과거 시각을 기록할 수 있다 — 예: 동시 재배정에서 `unassignedAt < assignedAt` (P6에서 발견).
 * 따라서 잠금을 잡기 전에는 [now]를 읽지 않는다.
 */
class CommandContext(
    val actor: Actor,
    val correlationId: UUID,
    val idempotencyKey: String,
    private val clock: Clock,
) {
    private var stamped: Instant? = null

    val now: Instant
        get() = stamped ?: clock.instant().also { stamped = it }
}

/** HTTP 계층에서 해석한 command 메타데이터 ([CommandRequestArgumentResolver]). */
data class CommandRequest(
    /** `METHOD /route/{template}` — 같은 키를 다른 리소스에 재사용하면 payload hash가 달라 충돌로 감지된다. */
    val endpoint: String,
    val idempotencyKey: String,
    val correlationId: UUID,
)

data class CommandResult<T : Any>(val body: T, val status: HttpStatus = HttpStatus.OK)

data class CommandOutcome<T : Any>(
    val status: HttpStatus,
    val body: T,
    val replayed: Boolean,
) {
    fun toResponseEntity(): ResponseEntity<T> =
        ResponseEntity.status(status)
            .header(REPLAYED_HEADER, replayed.toString())
            .body(body)

    companion object {
        const val REPLAYED_HEADER = "Idempotency-Replayed"
    }
}
