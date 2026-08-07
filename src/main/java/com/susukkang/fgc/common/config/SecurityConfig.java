package com.susukkang.fgc.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
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
     * /api/** - 미인증이면 리다이렉트 대신 항상 401을 돌려준다.
     *
     * 세션 인증을 막지 않는 이유
     *  SessionCreationPolicy.STATELESS 를 걸면 폼 로그인 세션으로는 /api/** 를 못 부른다.
     *  그런데 인터페이스정의서 3-4 는 "화면 스크립트가 401 을 받으면 location='/login'" 이라고
     *  적어 두었다. 즉 화면이 세션 쿠키로 /api/** 를 Ajax 호출하는 것이 설계된 동작이다.
     *  STATELESS 를 켜면 로그인한 사용자의 화면 Ajax 가 전부 401 이 된다.
     *
     * ⚠ CSRF 를 끈 것은 지금 /api/** 에 GET 밖에 없어서다(상태변경 엔드포인트 0개).
     *  위와 같이 쿠키로도 인증되므로 CSRF 공격 시나리오는 성립한다.
     *  첫 POST/PUT/DELETE 엔드포인트(TRAN-W02 지급 확정 등)를 만들기 전에 반드시
     *  CSRF 를 다시 켜고 화면 스크립트가 X-CSRF-TOKEN 을 싣도록 바꿔야 한다.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
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
    @Order(2)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/assets/**", "/error").permitAll()
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
