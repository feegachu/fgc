package com.susukkang.fgc.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * @PreAuthorize 등 메서드 단위 인가 애노테이션을 활성화하고, REST API(/api/**)에 대해서만
 * CSRF 보호를 끈다.
 *
 * ★ 왜 /api/** 만 CSRF를 끄나
 *   CSRF는 브라우저가 쿠키를 자동으로 실어 보내는 상황(세션 기반 화면 로그인)을 노리는 공격이다.
 *   /api/** 는 매 요청마다 인증 헤더(Basic/향후 토큰)를 실어 보내는 순수 REST 클라이언트가
 *   호출하므로 이 공격 시나리오가 적용되지 않는다 — Spring Security 공식 문서도 이런 API는
 *   CSRF를 꺼도 된다고 안내한다. 반대로 Thymeleaf 화면 경로는 세션 쿠키를 쓰므로 CSRF 보호를
 *   그대로 유지해야 한다.
 *
 * ★ 커스텀 SecurityFilterChain 빈을 정의하면 Spring Boot의 기본 체인
 *   (SpringBootWebSecurityConfiguration의 @ConditionalOnDefaultWebSecurity 로 동작하는 것)이
 *   자동으로 비활성화된다. 그래서 기존과 같은 동작(모든 요청 인증 필요, 폼 로그인·Basic 인증
 *   둘 다 허용)을 여기서 명시적으로 다시 선언한다 — 동작 자체를 바꾸는 것이 아니라
 *   "CSRF 예외 하나만 추가"하는 것이 목적이다.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }
}
