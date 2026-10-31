package com.susukkang.fgc.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import java.util.LinkedHashSet;
import java.util.List;
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

    // 실패 응답 봉투. springdoc은 단순 클래스명을 스키마 이름으로 쓰므로 Java 클래스명과 겹치지 않게 둔다.
    private static final String FAILURE_RESPONSE = "ApiFailureResponse";
    // ApiError에서 오류 종류에 따라 비는 속성.
    private static final List<String> NULLABLE_ERROR_FIELDS = List.of("field", "detail");
    // 인터페이스정의서 §2-1 2차 행: 401 본문은 인증 상태에 따라 있을 수도 없을 수도 있다.
    private static final String UNAUTHORIZED_BODY = "토큰이 없으면 본문 없음. Bearer 토큰이 만료·위조·무효화된 로그인이면 "
            + FAILURE_RESPONSE + "(FGC-AUTH-002·FGC-AUTH-004). 클라이언트는 401 본문을 JSON이라고 가정하지 않는다.";

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

    /**
     * 4xx·5xx 응답을 GlobalExceptionHandler가 실제로 내는 실패 봉투(data null, error 필수)로 바꾼다.
     * description만 적은 @ApiResponse에는 springdoc이 성공 반환 타입을 붙이기 때문이다.
     * 401은 토큰 없는 요청이 본문 없이 끝나므로(SecurityConfig HttpStatusEntryPoint) 본문 타입을 두지 않고 설명으로 적는다.
     */
    @Bean
    public OpenApiCustomizer failureResponses() {
        return api -> {
            var schemas = api.getComponents().getSchemas();
            var apiError = schemas.get("ApiError");
            if (apiError == null) return;
            NULLABLE_ERROR_FIELDS.forEach(property -> apiError.addProperty(property,
                    new Schema<>().types(new LinkedHashSet<>(List.of("string", "null")))));
            api.getComponents().addSchemas(FAILURE_RESPONSE, new Schema<>()
                    .types(Set.of("object"))
                    .addProperty("data", new Schema<>().types(Set.of("null")))
                    .addProperty("error", new Schema<>().$ref("#/components/schemas/ApiError"))
                    .addProperty("requestId", new Schema<>().types(Set.of("string")))
                    .required(List.of("data", "error", "requestId")));
            if (api.getPaths() == null) return;
            api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                if (operation.getResponses() == null) return;
                operation.getResponses().forEach((status, response) -> {
                    if (!status.startsWith("4") && !status.startsWith("5")) return;
                    if ("401".equals(status)) {
                        response.setContent(null);
                        var description = response.getDescription();
                        if (description == null || description.isBlank()) response.setDescription(UNAUTHORIZED_BODY);
                        else if (!description.contains(UNAUTHORIZED_BODY)) response.setDescription(description + " — " + UNAUTHORIZED_BODY);
                        return;
                    }
                    response.setContent(new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                            new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + FAILURE_RESPONSE))));
                });
            }));
        };
    }
}
