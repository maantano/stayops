package com.stayops.demo

import org.slf4j.LoggerFactory
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * E2E 실행마다 같은 초기 상태에서 시작하도록 스키마를 비우고 다시 migration 한다 (`e2e` profile 전용).
 * 안전장치: DB 이름이 `_e2e`로 끝나지 않으면 거부한다(개발·운영 DB를 지우지 않도록).
 */
@Configuration
@Profile("e2e")
class E2eDatabaseReset {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun cleanThenMigrate(): FlywayMigrationStrategy = FlywayMigrationStrategy { flyway ->
        val url = flyway.configuration.dataSource.connection.use { it.metaData.url }
        check(url.substringBefore('?').endsWith("_e2e")) { "refusing to clean a non-e2e database: $url" }
        log.warn("[e2e] cleaning and re-migrating {}", url)
        flyway.clean()
        flyway.migrate()
    }
}
