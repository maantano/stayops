package com.stayops.staff.api

import com.stayops.identity.Actor
import com.stayops.staff.application.StaffWorkloadReport
import com.stayops.staff.application.StaffWorkloadService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
class StaffWorkloadController(private val service: StaffWorkloadService) {
    /** [date]는 property timezone의 업무일(YYYY-MM-DD). 생략 시 오늘. */
    @GetMapping("/api/v1/properties/{propertyId}/staff-workloads")
    fun workloads(@PathVariable propertyId: UUID, @RequestParam(required = false) date: LocalDate?, actor: Actor): StaffWorkloadReport =
        service.workloads(actor, propertyId, date)
}
