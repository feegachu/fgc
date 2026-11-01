package com.susukkang.fgc.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import java.util.Set;

/**
 * 설명 : springdoc 에 2차 JWT Bearer 보안 스키마를 등록한다(#400, 인터페이스정의서 §2-1-1).
 * Swagger UI 의 [Authorize] 에 POST /api/v1/auth/login 응답의 accessToken 을 넣으면 Authorization 헤더로 붙는다.
 * 세션(폼 로그인)으로 연 Swagger UI 는 지금처럼 쿠키로도 동작한다.
 */
@Configuration
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@OpenAPIDefinition(security = @SecurityRequirement(name = "bearerAuth"))
public class OpenApiConfig {

    // 응답 스키마 → null이 올 수 있는 $ref 속성. 3.1에서는 @Schema(nullable = true)가 $ref 옆에서 사라진다.
    private static final Map<String, String> NULLABLE_REFERENCES = Map.of(
            "ClawbackCandidateDetailResponse", "latestCalculation",
            "ContractStatusEventResponse", "registeredBy");

    /** OpenAPI 3.1에서 nullable $ref를 참조 대상 또는 null의 합집합으로 표현한다. */
    @Bean
    public OpenApiCustomizer nullableReferences() {
        return api -> NULLABLE_REFERENCES.forEach((schemaName, property) -> {
            var owner = api.getComponents().getSchemas().get(schemaName);
            if (owner == null || owner.getProperties() == null) return;
            var reference = (Schema<?>) owner.getProperties().get(property);
            if (reference == null || reference.get$ref() == null) return;
            owner.addProperty(property, new ComposedSchema()
                    .addAnyOfItem(new Schema<>().$ref(reference.get$ref()))
                    .addAnyOfItem(new Schema<>().types(Set.of("null"))));
        });
    }
}
