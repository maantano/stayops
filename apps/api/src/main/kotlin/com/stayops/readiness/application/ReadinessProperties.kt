package com.stayops.readiness.application

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * readiness 설정. 값은 모두 `ASSUMPTION`이며 현장 표준이 아니다 (docs/v4.1/02 §8 규칙 8, OPEN-7).
 * - [warningMarginMinutes]: 마감보다 이만큼 이내로 여유가 적으면 WARNING
 * - [evaluationWindowHours]: 지금부터 이 시간 안에 체크인 예정인 확정 예약만 평가
 */
@ConfigurationProperties("stayops.readiness")
data class ReadinessProperties(
    val warningMarginMinutes: Long,
    val evaluationWindowHours: Long,
    val calculationVersion: String,
) {
    init {
        require(warningMarginMinutes >= 0) { "warning margin must be >= 0" }
        require(evaluationWindowHours > 0) { "evaluation window must be > 0" }
    }
}
