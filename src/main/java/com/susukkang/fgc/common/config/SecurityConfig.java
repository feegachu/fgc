package com.susukkang.fgc.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * @PreAuthorize 등 메서드 단위 인가 애노테이션을 활성화하고, REST API(/api/**)에 대해서만 CSRF 보호를 끔
 *   CSRF는 브라우저가 쿠키를 자동으로 실어 보내는 상황(세션 기반 화면 로그인)을 노리는 공격
 *   /api/** 는 매 요청마다 인증 헤더(Basic/향후 토큰)를 실어 보내는 순수 REST 클라이언트가
 *   호출하므로 이 공격 시나리오가 적용되지 않음
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
