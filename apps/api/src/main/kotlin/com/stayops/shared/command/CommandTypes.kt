package com.stayops.shared.command

import com.stayops.identity.Actor
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.time.Instant
import java.util.UUID

/** command 하나의 실행 문맥. 서비스에 명시적으로 전달한다. [now]는 command 시작 시각 하나로 고정한다. */
data class CommandContext(
    val actor: Actor,
    val correlationId: UUID,
    val idempotencyKey: String,
    val now: Instant,
)

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
