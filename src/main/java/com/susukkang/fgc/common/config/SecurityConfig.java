package com.susukkang.fgc.common.config;

import com.susukkang.fgc.common.security.Roles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

// FUN-001 개발 순서 1

/**
 * FUN-001 로그인·로그아웃 / 인터페이스정의서 2-1-1(1차는 세션 방식), 5-3(MPA 302·Ajax 401).
 *
 * 체인을 둘로 나눈 이유
 *  한 체인에 formLogin(loginPage)과 httpBasic을 같이 두면 인증 진입점이 하나로 합쳐진다.
 *  그러면 미인증 REST 호출까지 로그인 화면으로 302 되어 화면 스크립트가 401을 구분할 수 없다.
 *  인터페이스정의서 5-3의 "MPA = /login 302 / Ajax = 401 JSON" 규칙을 체인 분리로 강제한다.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * FUN-065 지급 등록·수정·확정 API는 세션 인증과 CSRF 토큰을 함께 검증한다.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain commissionPaymentApiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/v1/transactions/**")
                // 2026-08-11 yslee - 세션 쿠키 기반 지급 API의 CSRF 보호 활성화
                // 기존 코드: /api/** 전체에서 CSRF 검증을 비활성화
                // 문제: 로그인 세션을 악용한 외부 사이트가 지급 등록·수정·확정 요청을 위조할 수 있음
                // 개선: FUN-065 상태 변경 요청에 Spring Security 기본 CSRF 토큰 검증을 우선 적용
                .csrf(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        // FUN-002(#82) — COMPLIANCE는 역할 정의(§4-1)상 "조회만". 컨트롤러
                        // @PreAuthorize를 빠뜨려도 최소한 이 굵은 규칙이 COMPLIANCE의 상태 변경
                        // 요청을 막는다. 세분화된 역할 구분(SETTLEMENT vs GA_ADMIN 등)은
                        // 여전히 컨트롤러 @PreAuthorize가 담당한다.
                        .requestMatchers(HttpMethod.POST, "/**").hasAnyRole(Roles.NON_COMPLIANCE)
                        .requestMatchers(HttpMethod.PUT, "/**").hasAnyRole(Roles.NON_COMPLIANCE)
                        .requestMatchers(HttpMethod.DELETE, "/**").hasAnyRole(Roles.NON_COMPLIANCE)
                        .requestMatchers(HttpMethod.PATCH, "/**").hasAnyRole(Roles.NON_COMPLIANCE)
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /**
     * /api/** - 미인증이면 리다이렉트 대신 항상 401을 돌려준다.
     *
     * 세션 인증을 막지 않는 이유
     *  SessionCreationPolicy.STATELESS 를 걸면 폼 로그인 세션으로는 /api/** 를 못 부른다.
     *  그런데 인터페이스정의서 3-4 는 "화면 스크립트가 401 을 받으면 location='/login'" 이라고
     *  적어 두었다. 즉 화면이 세션 쿠키로 /api/** 를 Ajax 호출하는 것이 설계된 동작이다.
     *  STATELESS 를 켜면 로그인한 사용자의 화면 Ajax 가 전부 401 이 된다.
     *
     * ⚠ 이 체인은 아직 CSRF 전환을 마치지 않은 나머지 /api/**의 호환용 체인이다.
     *  FUN-065 지급 API는 위의 우선 체인에서 CSRF를 검증한다. 다른 상태 변경 API도
     *  담당 화면이 X-CSRF-TOKEN을 전송하도록 준비한 뒤 보호 체인으로 전환해야 한다.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // 감사로그 API(IF-API-52)는 아직 미구현이지만 구현 시점에 바로
                        // 적용되도록 선제 등록한다.
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit-logs/**")
                        .hasAnyRole(Roles.COMPLIANCE, Roles.SYSTEM_ADMIN)
                        // FUN-002(#82) 굵은 규칙 — commissionPaymentApiSecurityFilterChain 주석 참고.
                        .requestMatchers(HttpMethod.POST, "/**").hasAnyRole(Roles.NON_COMPLIANCE)
                        .requestMatchers(HttpMethod.PUT, "/**").hasAnyRole(Roles.NON_COMPLIANCE)
                        .requestMatchers(HttpMethod.DELETE, "/**").hasAnyRole(Roles.NON_COMPLIANCE)
                        .requestMatchers(HttpMethod.PATCH, "/**").hasAnyRole(Roles.NON_COMPLIANCE)
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /**
     * 화면(MPA) - 세션 쿠키 기반 폼 로그인. CSRF는 켜 둔다(Thymeleaf th:action이 토큰을 자동 주입).
     *
     * 실패 시 /login?error 하나로만 보낸다. 아이디 없음 / 비밀번호 틀림 / 잠긴 계정을 화면에서
     * 구분하면 계정 존재 여부가 새어 나간다(화면정의서 AUTH-W01 "막아야 할 것").
     */
    @Bean
    @Order(3)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/assets/**", "/css/**", "/js/**",
                                "/images/**", "/fonts/**", "/favicon.ico", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/audit-logs")
                        .hasAnyRole(Roles.COMPLIANCE, Roles.SYSTEM_ADMIN)
                        // FUN-002(#82) 굵은 규칙 — commissionPaymentApiSecurityFilterChain 주석 참고.
                        .requestMatchers(HttpMethod.POST, "/**").hasAnyRole(Roles.NON_COMPLIANCE)
                        .requestMatchers(HttpMethod.PUT, "/**").hasAnyRole(Roles.NON_COMPLIANCE)
                        .requestMatchers(HttpMethod.DELETE, "/**").hasAnyRole(Roles.NON_COMPLIANCE)
                        .requestMatchers(HttpMethod.PATCH, "/**").hasAnyRole(Roles.NON_COMPLIANCE)
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error"))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"));
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
