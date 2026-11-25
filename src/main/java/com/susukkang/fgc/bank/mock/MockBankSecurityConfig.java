package com.susukkang.fgc.bank.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 설명 : 모의 은행 주소(/api/v1/mock-bank/**)만 맡는 보안 체인. 모의 은행을 켰을 때만 생긴다.
 *
 * 모의 은행은 FGC 로그인과 무관한 다른 시스템을 흉내 내므로, 자기가 발급한 토큰을 직접 확인한다(MockBankController).
 * FGC의 체인에 두면 은행 토큰을 FGC의 JWT로 풀려다 401 이 된다. 그래서 이 주소만 따로 떼어 낸다.
 * 모의 은행을 끄면 이 체인이 없어 같은 주소는 FGC 체인의 인증 요구(401)로 막힌다.
 *
 * 동의 화면의 폼(동의, 취소)은 브라우저가 보낸다. 화면을 줄 때 쿠키와 숨은 입력에 같은 값을 싣고, 폼이 돌아올 때 둘을
 * 대조해 다른 사이트가 사용자 대신 보낸 폼을 거절한다. 세션은 쓰지 않는다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-18
 */
@Configuration
@ConditionalOnProperty(name = "fgc.openbanking.mock.enabled", havingValue = "true")
public class MockBankSecurityConfig {

    /** 쿠키와 브라우저 출처가 없는 서버 간 토큰 교환만 공통 무상태 API 체인에 맡긴다. */
    @Bean
    RequestMatcher mockBankServerTokenExchange() {
        return new AndRequestMatcher(
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/mock-bank/oauth/2.0/token"),
                request -> request.getHeader(HttpHeaders.COOKIE) == null && request.getCookies() == null
                        && request.getHeader(HttpHeaders.ORIGIN) == null && request.getHeader("Sec-Fetch-Mode") == null);
    }

    @Bean
    @Order(0)
    public SecurityFilterChain mockBankFilterChain(HttpSecurity http) throws Exception {
        // FGC 화면이 쓰는 쿠키와 섞이지 않게 이름과 경로를 모의 은행 것으로 좁힌다.
        CookieCsrfTokenRepository formTokens = new CookieCsrfTokenRepository();
        formTokens.setCookieName("MOCK-BANK-XSRF");
        formTokens.setCookiePath("/api/v1/mock-bank");
        http.securityMatcher(new AndRequestMatcher(
                        PathPatternRequestMatcher.withDefaults().matcher("/api/v1/mock-bank/**"),
                        new NegatedRequestMatcher(mockBankServerTokenExchange())))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.csrfTokenRepository(formTokens))
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
