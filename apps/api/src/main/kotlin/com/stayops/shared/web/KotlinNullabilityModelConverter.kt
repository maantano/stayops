package com.stayops.shared.web

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.media.Schema
import org.springframework.stereotype.Component
import kotlin.reflect.full.memberProperties

/**
 * Kotlin의 non-null 프로퍼티를 OpenAPI `required`로 표시한다 (ADR-006).
 * 기본 springdoc은 Kotlin nullability를 스키마에 반영하지 않아 생성된 FE 타입의 모든 필드가 optional이 된다.
 * 응답 DTO에서 null이 될 수 없는 필드는 FE에서도 필수로 다뤄야 계약이 정확하다.
 */
@Component
class KotlinNullabilityModelConverter : ModelConverter {

    override fun resolve(type: AnnotatedType, context: ModelConverterContext, chain: Iterator<ModelConverter>): Schema<*>? {
        val schema = if (chain.hasNext()) chain.next().resolve(type, context, chain) else null
        val raw = runCatching { Json.mapper().constructType(type.type).rawClass }.getOrNull() ?: return schema
        if (raw.isEnum || !raw.isAnnotationPresent(Metadata::class.java)) return schema

        val name = schema?.`$ref`?.substringAfterLast('/') ?: schema?.name
        val model: Schema<*> = name?.let { context.definedModels[it] } ?: schema ?: return schema
        val properties = model.properties ?: return schema
        val required = runCatching {
            raw.kotlin.memberProperties.filter { !it.returnType.isMarkedNullable }.map { it.name }.filter { it in properties.keys }
        }.getOrDefault(emptyList())
        if (required.isNotEmpty()) model.required = (model.required.orEmpty() + required).distinct().sorted()
        return schema
    }
}
