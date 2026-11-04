package com.stayops.dashboard

import com.stayops.identity.Actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class DashboardController(private val service: DashboardService) {
    @GetMapping("/api/v1/properties/{propertyId}/dashboard")
    fun dashboard(@PathVariable propertyId: UUID, actor: Actor): Dashboard = service.dashboard(actor, propertyId)
}
