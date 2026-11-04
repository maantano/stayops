package com.stayops.housekeeping.application

import com.stayops.housekeeping.domain.TaskType
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 작업 예상 소요시간 기본값 (분). 사용자가 값을 주지 않으면 이 값을 쓰고 `durationSource=CONFIG_DEFAULT`로 표시한다.
 * 모든 값은 합성 시뮬레이션용 `ASSUMPTION`이며 업계 표준·실측치가 아니다 (v4.1 01 §8).
 */
@ConfigurationProperties("stayops.housekeeping")
data class HousekeepingProperties(
    val defaultDurationMinutes: Map<TaskType, Int>,
) {
    init {
        TaskType.entries.forEach { type ->
            val minutes = requireNotNull(defaultDurationMinutes[type]) { "stayops.housekeeping.default-duration-minutes.$type is required" }
            require(minutes > 0) { "default duration for $type must be > 0" }
        }
    }

    fun defaultDuration(type: TaskType): Int = defaultDurationMinutes.getValue(type)
}
