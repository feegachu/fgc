package com.susukkang.fgc.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.service.FgcJwtAuthenticationConverter;
import com.susukkang.fgc.auth.service.JwtSessionRevokedException;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.security.AgentRequestAuthorization;
import com.susukkang.fgc.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

// FUN-001 개발 순서 1

/**
 * FUN-001 로그인·로그아웃 / 인터페이스정의서 §2-1-1(2차 JWT).
 * <p>
 * 인증은 Bearer Access 토큰 하나다. 미인증 요청은 리다이렉트 없이 401 이고(화면은 SPA 가 /login 으로 보낸다),
 * 권한이 없으면 403 + ApiResponse 봉투다.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * FUN-002(#82) — 필터 단계(아래 URL 굵은 규칙)에서 거부된 /api/** 요청도 컨트롤러
     *
     * @PreAuthorize 거부와 같은 ApiResponse 봉투(FGC-AUTH-003)로 나가게 한다.
     * 기본 핸들러는 sendError(403) → Boot 기본 오류 JSON이라 인터페이스정의서 3-3의
     * 봉투 규칙(이슈 #82 인수조건 "403 + ApiResponse 오류 봉투")을 깬다.
     * 매핑을 복사하지 않고 GlobalExceptionHandler.handleAccessDenied 를 그대로 재사용한다.
     */
    @Bean
    public AccessDeniedHandler apiAccessDeniedHandler(
            GlobalExceptionHandler globalExceptionHandler, ObjectMapper objectMapper) {
        return (request, response, exception) -> {
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                return;
            }
            writeEnvelope(response, globalExceptionHandler.handleAccessDenied(exception), objectMapper);
        };
    }

    /**
     * #400 — Bearer 토큰 인증 실패(만료·서명 위조·무효화된 로그인)를 401 + ApiResponse 봉투로 내보낸다.
     * 중복 로그인으로 밀려난 로그인은 FGC-AUTH-004, 그 밖에는 FGC-AUTH-002(로그인 만료).
     * WWW-Authenticate 헤더는 표준 진입점(RFC 6750)이 붙인다.
     */
    @Bean
    public AuthenticationEntryPoint bearerAuthenticationEntryPoint(
            GlobalExceptionHandler globalExceptionHandler, ObjectMapper objectMapper) {
        BearerTokenAuthenticationEntryPoint standard = new BearerTokenAuthenticationEntryPoint();
        return (request, response, exception) -> {
            standard.commence(request, response, exception);
            FgcErrorCode errorCode = exception instanceof JwtSessionRevokedException revoked
                    ? revoked.getErrorCode()
                    : FgcErrorCode.AUTH_002;
            writeEnvelope(response,
                    globalExceptionHandler.handleBusinessException(new FgcBusinessException(errorCode)),
                    objectMapper);
        };
    }

    private static void writeEnvelope(HttpServletResponse response, ResponseEntity<ApiResponse<Void>> entity,
                                      ObjectMapper objectMapper) throws IOException {
        response.setStatus(entity.getStatusCode().value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), entity.getBody());
    }

    /**
     * Refresh 쿠키로 동작하는 토큰 API. 로그인 전에 부르므로 인증을 요구하지 않는다(COMPLIANCE 도 막지 않는다).
     * CSRF 는 AuthTokenController 가 Origin·X-FGC-Client 로 막는다(인터페이스정의서 §2-1-1 "CSRF 판정").
     */
    private static final String[] AUTH_TOKEN_ENDPOINTS = {
            "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout"};

    /**
     * 웨이브 C(#419, ADR-02 3단계) — 화면은 SPA 가 nginx 에서 받으므로 서버 체인은 하나다.
     * 세션을 만들지도 읽지도 않고(STATELESS) Bearer 토큰만 받는다. 쿠키로 인증하지 않으니 CSRF 토큰 검사도 끈다.
     * <p>
     * 디코더·Converter 를 요청 시점에 꺼내는 이유: SecurityConfig 를 import 하는 @WebMvcTest 슬라이스에는
     * JwtConfig·FgcUserDetailsService 가 없다. 빈이 없는 채로 Bearer 요청이 오면 500 으로 실패한다(통과시키지 않는다).
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, AccessDeniedHandler apiAccessDeniedHandler,
            ObjectProvider<JwtDecoder> jwtDecoder,
            ObjectProvider<FgcJwtAuthenticationConverter> jwtConverter,
            AuthenticationEntryPoint bearerAuthenticationEntryPoint) throws Exception {
        http
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt
                                .decoder(token -> jwtDecoder.getObject().decode(token))
                                .jwtAuthenticationConverter(token -> jwtConverter.getObject().convert(token)))
                        .authenticationEntryPoint(bearerAuthenticationEntryPoint))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                // 로그아웃은 POST /api/v1/auth/logout 이다. 기본 LogoutFilter(POST /logout)를 남기지 않는다.
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, AUTH_TOKEN_ENDPOINTS).permitAll()
                        // API 문서는 공개하고 실제 호출은 Swagger UI [Authorize]의 Bearer 로 한다(#419).
                        .requestMatchers("/error", "/actuator/health", "/actuator/health/**",
                                "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        // FUN-085: 새 AGENT에만 적용한다. 기존 4개 역할의 아래 규칙은 유지한다.
                        .requestMatchers(AgentRequestAuthorization.IS_AGENT).access(AgentRequestAuthorization.SCOPED_READS)
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit-logs/**")
                        .hasAnyRole(Roles.COMPLIANCE, Roles.SYSTEM_ADMIN)
                        // FUN-002(#82) — COMPLIANCE는 역할 정의(§4-1)상 "조회만". 컨트롤러
                        // @PreAuthorize를 빠뜨려도 최소한 이 굵은 규칙이 COMPLIANCE의 상태 변경
                        // 요청을 막는다. 세분화된 역할 구분(SETTLEMENT vs GA_ADMIN 등)은
                        // 여전히 컨트롤러 @PreAuthorize가 담당한다.
                        // Read-only assistant query; retain the JWT chain and AGENT guard above.
                        .requestMatchers(HttpMethod.POST, "/api/v1/assistant/calculations").hasAnyRole("SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE")
                        .requestMatchers(HttpMethod.POST, "/**").hasAnyRole(Roles.NON_COMPLIANCE)
                        .requestMatchers(HttpMethod.PUT, "/**").hasAnyRole(Roles.NON_COMPLIANCE)
                        .requestMatchers(HttpMethod.DELETE, "/**").hasAnyRole(Roles.NON_COMPLIANCE)
                        .requestMatchers(HttpMethod.PATCH, "/**").hasAnyRole(Roles.NON_COMPLIANCE)
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(apiAccessDeniedHandler));
        return http.build();
    }

    /**
     * 시드(V3__seed_demo_data.sql)의 해시가 접두사 없는 순수 BCrypt($2a$10$...)다.
     * DelegatingPasswordEncoder를 쓰면 {bcrypt} 접두사가 없어 전부 인증 실패한다.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
