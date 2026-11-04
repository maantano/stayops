package com.stayops.readiness.api

import com.stayops.identity.Actor
import com.stayops.readiness.application.ReadinessQueryService
import com.stayops.readiness.application.ReadinessReport
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class ReadinessController(private val service: ReadinessQueryService) {
    /** 도착 예정 객실의 준비 위험과 계산 근거 (docs/v4.1/02 §8). 예측 보장이 아니라 입력 기반 계산값이다. */
    @GetMapping("/api/v1/properties/{propertyId}/readiness")
    fun readiness(@PathVariable propertyId: UUID, actor: Actor): ReadinessReport = service.readiness(actor, propertyId)
}
