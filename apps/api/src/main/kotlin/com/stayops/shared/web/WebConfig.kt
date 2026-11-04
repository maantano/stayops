package com.stayops.shared.web

import com.stayops.identity.DemoActorArgumentResolver
import com.stayops.shared.command.CommandRequestArgumentResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Clock

@Configuration
class WebConfig(
    private val actorResolver: DemoActorArgumentResolver,
    private val commandRequestResolver: CommandRequestArgumentResolver,
) : WebMvcConfigurer {

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(actorResolver)
        resolvers.add(commandRequestResolver)
    }
}

@Configuration
class ClockConfig {
    /** 모든 시각은 UTC Instant (v4.1 02 §1-7). 테스트에서 교체할 수 있도록 bean으로 둔다. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
