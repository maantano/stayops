package com.stayops.housekeeping.api

import com.stayops.housekeeping.application.TaskListItem
import com.stayops.housekeeping.application.TaskQueryService
import com.stayops.housekeeping.domain.TaskStatus
import com.stayops.housekeeping.domain.TaskType
import com.stayops.identity.Actor
import com.stayops.shared.web.PageResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
class TaskQueryController(private val service: TaskQueryService) {
    /** 작업 목록. status·type은 반복 지정 가능. HOUSEKEEPER·INSPECTOR는 본인 배정 작업만 (staffId 무시). */
    @GetMapping("/api/v1/properties/{propertyId}/tasks")
    fun tasks(
        @PathVariable propertyId: UUID,
        @RequestParam(required = false) status: Set<TaskStatus>?,
        @RequestParam(required = false) type: Set<TaskType>?,
        @RequestParam(required = false) staffId: UUID?,
        @RequestParam(required = false) roomId: UUID?,
        @RequestParam(required = false) scheduledFrom: Instant?,
        @RequestParam(required = false) scheduledTo: Instant?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
        actor: Actor,
    ): PageResponse<TaskListItem> = service.tasks(
        actor, propertyId,
        TaskQueryService.Filter(status.orEmpty(), type.orEmpty(), staffId, roomId, scheduledFrom, scheduledTo),
        cursor, limit,
    )
}
