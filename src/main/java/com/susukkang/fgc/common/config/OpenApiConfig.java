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

    /** OpenAPI 3.1에서 nullable $ref를 참조 대상 또는 null의 합집합으로 표현한다. */
    @Bean
    public OpenApiCustomizer clawbackCalculationNullability() {
        return api -> {
            var detail = api.getComponents().getSchemas().get("ClawbackCandidateDetailResponse");
            if (detail == null || detail.getProperties() == null) return;
            var calculation = (Schema<?>) detail.getProperties().get("latestCalculation");
            if (calculation == null || calculation.get$ref() == null) return;
            detail.addProperty("latestCalculation", new ComposedSchema()
                    .addAnyOfItem(new Schema<>().$ref(calculation.get$ref()))
                    .addAnyOfItem(new Schema<>().types(Set.of("null"))));
        };
    }
}
