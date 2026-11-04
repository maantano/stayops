package com.stayops.shared.web

import com.stayops.identity.Actor
import com.stayops.identity.DemoActorArgumentResolver
import com.stayops.shared.command.CommandRequest
import com.stayops.shared.command.CommandRequestArgumentResolver
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.customizers.OperationCustomizer
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping

/**
 * OpenAPI 계약 (ADR-006). `Actor`·`CommandRequest`는 헤더에서 해석되는 인자이므로 파라미터 목록에서 숨기고
 * 대신 헤더로 문서화한다. 모든 오류는 RFC 9457 Problem Details(`application/problem+json`)로 응답한다.
 */
@Configuration
class OpenApiConfig {

    init {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Actor::class.java, CommandRequest::class.java)
    }

    @Bean
    fun stayOpsOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("StayOps API")
                .version("v1")
                .description(
                    "객실 준비·하우스키핑 운영 API (합성 데이터 데모). 인증은 데모 헤더 `X-Demo-Staff-Id`이며 보안 수단이 아니다 (ADR-005). " +
                        "규칙의 기준 문서: docs/v4.1/02_DOMAIN_AND_API.md",
                ),
        )
        .servers(listOf(Server().url("/").description("같은 origin (환경별 base URL은 클라이언트 설정)")))
        .components(Components().addSchemas("Problem", problemSchema()))

    @Bean
    fun stayOpsHeaders(): OperationCustomizer = OperationCustomizer { operation, handlerMethod ->
        val params = handlerMethod.methodParameters.map { it.parameterType }
        if (Actor::class.java in params) {
            operation.addParametersItem(
                HeaderParameter().name(DemoActorArgumentResolver.HEADER).required(true)
                    .description("데모 요청자(활성 직원 id). 보안 수단 아님 (ADR-005)").schema(StringSchema().format("uuid")),
            )
        }
        if (CommandRequest::class.java in params) {
            operation.addParametersItem(
                HeaderParameter().name(CommandRequestArgumentResolver.IDEMPOTENCY_KEY_HEADER).required(true)
                    .description("사용자 행동 1회당 1개. 재시도에는 같은 값을 쓴다 (02 §7)").schema(StringSchema().maxLength(100).pattern("^[A-Za-z0-9_\\-:.]{1,100}$")),
            )
        }
        operation.addParametersItem(
            HeaderParameter().name(CorrelationIdFilter.HEADER).required(false)
                .description("선택. 없으면 서버가 생성해 응답 헤더로 돌려준다").schema(StringSchema().format("uuid")),
        )
        val isCommand = handlerMethod.hasMethodAnnotation(PostMapping::class.java) || handlerMethod.hasMethodAnnotation(DeleteMapping::class.java)
        val problem = Content().addMediaType("application/problem+json", MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/Problem")))
        val codes = if (isCommand) listOf("400", "401", "403", "404", "409") else listOf("400", "401", "403", "404")
        codes.forEach { code ->
            if (operation.responses?.containsKey(code) != true) {
                operation.responses.addApiResponse(code, ApiResponse().description("Problem Details (code 필드 참조)").content(problem))
            }
        }
        operation
    }

    private fun problemSchema(): Schema<Any> {
        @Suppress("UNCHECKED_CAST")
        val schema = ObjectSchema() as Schema<Any>
        schema.description = "RFC 9457 Problem Details + StayOps 확장 필드"
        schema.addProperty("type", StringSchema().example("urn:stayops:problem:invalid-state"))
        schema.addProperty("title", StringSchema())
        schema.addProperty("status", Schema<Int>().type("integer"))
        schema.addProperty("detail", StringSchema())
        schema.addProperty("instance", StringSchema())
        schema.addProperty("code", StringSchema().description("02 §7.1 오류 코드").example("INVALID_STATE"))
        schema.addProperty("correlationId", StringSchema().format("uuid"))
        schema.required = listOf("type", "title", "status", "code")
        return schema
    }
}
