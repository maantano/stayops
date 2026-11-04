package com.stayops.reservation.api

import com.stayops.identity.Actor
import com.stayops.reservation.application.ReservationListItem
import com.stayops.reservation.application.ReservationQueryService
import com.stayops.reservation.domain.ReservationStatus
import com.stayops.shared.web.PageResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
class ReservationQueryController(private val service: ReservationQueryService) {
    @GetMapping("/api/v1/properties/{propertyId}/reservations")
    fun reservations(
        @PathVariable propertyId: UUID,
        @RequestParam(required = false) status: Set<ReservationStatus>?,
        @RequestParam(required = false) checkInFrom: Instant?,
        @RequestParam(required = false) checkInTo: Instant?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
        actor: Actor,
    ): PageResponse<ReservationListItem> = service.reservations(actor, propertyId, status.orEmpty(), checkInFrom, checkInTo, cursor, limit)
}
