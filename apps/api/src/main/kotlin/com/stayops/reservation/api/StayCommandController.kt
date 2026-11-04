package com.stayops.reservation.api

import com.stayops.identity.Actor
import com.stayops.reservation.application.StayCommandResponse
import com.stayops.reservation.application.StayCommandService
import com.stayops.shared.command.CommandRequest
import com.stayops.shared.command.CommandResult
import com.stayops.shared.command.IdempotentCommandExecutor
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 예약 check-in / check-out (v4.1 02 §6). 요청 본문은 없다. 시각은 서버 기준. */
@RestController
@RequestMapping("/api/v1/reservations")
class StayCommandController(
    private val executor: IdempotentCommandExecutor,
    private val service: StayCommandService,
) {
    @PostMapping("/{reservationId}/check-in")
    fun checkIn(@PathVariable reservationId: UUID, actor: Actor, command: CommandRequest): ResponseEntity<StayCommandResponse> =
        executor.execute(command, actor, mapOf("reservationId" to reservationId), StayCommandResponse::class.java) { ctx ->
            CommandResult(service.checkIn(ctx, reservationId))
        }.toResponseEntity()

    @PostMapping("/{reservationId}/check-out")
    fun checkOut(@PathVariable reservationId: UUID, actor: Actor, command: CommandRequest): ResponseEntity<StayCommandResponse> =
        executor.execute(command, actor, mapOf("reservationId" to reservationId), StayCommandResponse::class.java) { ctx ->
            CommandResult(service.checkOut(ctx, reservationId))
        }.toResponseEntity()
}
