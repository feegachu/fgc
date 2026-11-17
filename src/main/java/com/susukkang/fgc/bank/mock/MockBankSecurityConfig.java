package com.susukkang.fgc.bank.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 설명 : 모의 은행 주소(/api/v1/mock-bank/**)만 맡는 보안 체인. 모의 은행을 켰을 때만 생긴다.
 *
 * 모의 은행은 FGC 로그인과 무관한 다른 시스템을 흉내 내므로, 자기가 발급한 토큰을 직접 확인한다(MockBankController).
 * FGC의 체인에 두면 은행 토큰을 FGC의 JWT로 풀려다 401 이 된다. 그래서 이 주소만 따로 떼어 낸다.
 * 모의 은행을 끄면 이 체인이 없어 같은 주소는 FGC 체인의 인증 요구(401)로 막힌다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-18
 */
@Configuration
@ConditionalOnProperty(name = "fgc.openbanking.mock.enabled", havingValue = "true")
public class MockBankSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain mockBankFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/v1/mock-bank/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
