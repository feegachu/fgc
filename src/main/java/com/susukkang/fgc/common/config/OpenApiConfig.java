package com.susukkang.fgc.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * 설명 : springdoc 에 2차 JWT Bearer 보안 스키마를 등록한다(#400, 인터페이스정의서 §2-1-1).
 * Swagger UI 의 [Authorize] 에 POST /api/v1/auth/login 응답의 accessToken 을 넣으면 Authorization 헤더로 붙는다.
 * 세션(폼 로그인)으로 연 Swagger UI 는 지금처럼 쿠키로도 동작한다.
 */
@Configuration
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@OpenAPIDefinition(security = @SecurityRequirement(name = "bearerAuth"))
public class OpenApiConfig {
}
