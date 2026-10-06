package com.susukkang.fgc.auth.controller;

import com.susukkang.fgc.auth.dto.AuthTokenDtos.LoginRequest;
import com.susukkang.fgc.auth.dto.AuthTokenDtos.MeResponse;
import com.susukkang.fgc.auth.dto.AuthTokenDtos.TokenResponse;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.AuthTokenService;
import com.susukkang.fgc.auth.service.AuthTokenService.IssuedTokens;
import com.susukkang.fgc.auth.service.AuthTokenService.RefreshResult;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.YearMonth;

/**
 * 설명 : 2차 JWT 토큰 API(#400, 인터페이스정의서 §2-1-1·§11-3).
 *
 * login·refresh·logout 은 Refresh 쿠키(자동 전송)를 쓰므로 Spring CSRF 토큰 대신 아래 방어를 건다
 * (SecurityConfig 가 이 세 경로만 CSRF 토큰 검사에서 뺀다 — 그래서 {@link #requireTrustedClient}는 빼먹으면 안 된다).
 *   ① 쿠키 SameSite=Strict ② Origin 이 서비스 출처와 같아야 함(없거나 null 이면 403) ③ X-FGC-Client: web 필수
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthTokenController {

    public static final String REFRESH_COOKIE = "fgc_refresh";
    static final String CLIENT_HEADER = "X-FGC-Client";
    private static final String CLIENT_WEB = "web";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final Expression CAN_PROCESS = PARSER.parseExpression(Roles.CAN_PROCESS);
    private static final Expression CAN_VIEW_AUDIT_LOG = PARSER.parseExpression(Roles.CAN_VIEW_AUDIT_LOG);
    private static final Expression CAN_HANDLE_EXCEPTION = PARSER.parseExpression(Roles.CAN_HANDLE_EXCEPTION);
    private static final Expression CAN_REVERSE_JOURNAL = PARSER.parseExpression(Roles.CAN_REVERSE_JOURNAL);
    private static final Expression CAN_FINALIZE_VALIDATION = PARSER.parseExpression(Roles.CAN_FINALIZE_VALIDATION);

    private final AuthTokenService authTokenService;
    private final AuthenticationConfiguration authenticationConfiguration;
    private final YearMonth demoMonth;

    public AuthTokenController(AuthTokenService authTokenService,
                               AuthenticationConfiguration authenticationConfiguration,
                               @Value("${fgc.demo-month}") String demoMonth) {
        this.authTokenService = authTokenService;
        this.authenticationConfiguration = authenticationConfiguration;
        this.demoMonth = YearMonth.parse(demoMonth);
    }

    /**
     * 폼 로그인과 같은 AuthenticationManager(FgcUserDetailsService + BCrypt)로 비밀번호를 검증한다.
     * 실패하면 사유(아이디 없음·비밀번호 틀림·잠김)를 구분하지 않고 FGC-AUTH-001 하나로 답한다.
     * 실패 감사(LOGIN_FAIL)는 기존과 같이 AuthenticationManager 가 발행한 실패 이벤트를 AuthAuditListener 가 남긴다.
     */
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest body,
                                            HttpServletRequest request,
                                            HttpServletResponse response) throws Exception {
        requireTrustedClient(request);
        Authentication authenticated;
        try {
            authenticated = authenticationConfiguration.getAuthenticationManager().authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.loginId(), body.password()));
        } catch (AuthenticationException e) {
            throw new FgcBusinessException(FgcErrorCode.AUTH_001);
        }
        IssuedTokens tokens = authTokenService.login((FgcUserDetails) authenticated.getPrincipal());
        return issued(tokens, response);
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        requireTrustedClient(request);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new FgcBusinessException(FgcErrorCode.AUTH_002);
        }
        RefreshResult result = authTokenService.refresh(refreshToken);
        if (result.error() != null) {
            // 무효가 된 쿠키를 브라우저에 남겨 두면 화면이 갱신을 반복 시도한다.
            response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString());
            throw new FgcBusinessException(result.error());
        }
        return issued(result.tokens(), response);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        requireTrustedClient(request);
        authTokenService.logout(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString());
        return ApiResponse.success();
    }

    /** 세션·Bearer 어느 쪽으로 인증했든 principal 은 FgcUserDetails 다(FgcJwtAuthenticationConverter). */
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(Authentication authentication) {
        FgcUserDetails user = (FgcUserDetails) authentication.getPrincipal();
        StandardEvaluationContext context = new StandardEvaluationContext(
                new SecurityExpressionRoot(authentication) { });
        return ApiResponse.success(new MeResponse(
                user.getUsername(), user.getUserName(), user.getRoleCode(),
                allowed(CAN_PROCESS, context),
                allowed(CAN_VIEW_AUDIT_LOG, context),
                allowed(CAN_HANDLE_EXCEPTION, context),
                allowed(CAN_REVERSE_JOURNAL, context),
                allowed(CAN_FINALIZE_VALIDATION, context), demoMonth.toString()));
    }

    /** @PreAuthorize 에 쓰는 Roles 상수를 그대로 평가한다 — 역할 조합을 여기서 다시 적지 않는다. */
    private static boolean allowed(Expression expression, StandardEvaluationContext context) {
        return Boolean.TRUE.equals(expression.getValue(context, Boolean.class));
    }

    private static ApiResponse<TokenResponse> issued(IssuedTokens tokens, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                refreshCookie(tokens.refreshToken(), tokens.refreshTokenMaxAge()).toString());
        return ApiResponse.success(new TokenResponse(
                tokens.accessToken(), AuthTokenService.TOKEN_TYPE, tokens.expiresInSeconds()));
    }

    private static ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }

    /**
     * ②③ 검사. "서비스 출처"는 요청이 도착한 출처(scheme://host[:port])다 — nginx 와 Vite 프록시가 Host 를
     * 그대로 넘기고(nginx/default.conf.template, frontend/vite.config.ts), 브라우저는 다른 사이트가 보낸 요청의
     * Host·Origin 을 위조할 수 없다.
     */
    private static void requireTrustedClient(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (!CLIENT_WEB.equals(request.getHeader(CLIENT_HEADER))
                || origin == null
                || !origin.equalsIgnoreCase(requestOrigin(request))) {
            throw new FgcBusinessException(FgcErrorCode.AUTH_003);
        }
    }

    private static String requestOrigin(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
    }
}
