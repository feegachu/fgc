package com.susukkang.fgc.common.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/** Dedicated scrape credential: never grants access to business APIs. Missing token fails closed. */
@Configuration(proxyBeanMethods = false)
public class MonitoringSecurityConfig {
    @Bean
    @Order(0)
    SecurityFilterChain prometheusSecurity(HttpSecurity http,
            @Value("${fgc.monitoring.scrape-token:}") String token) throws Exception {
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        return http.securityMatcher("/actuator/prometheus", "/actuator/prometheus/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(a -> a.anyRequest().access((authentication, context) -> {
                    String provided = context.getRequest().getHeader("Authorization");
                    return new AuthorizationDecision("GET".equals(context.getRequest().getMethod())
                            && !token.isBlank() && provided != null
                            && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8)));
                })).build();
    }
}
