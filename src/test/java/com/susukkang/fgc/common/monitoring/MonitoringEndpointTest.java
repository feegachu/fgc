package com.susukkang.fgc.common.monitoring;

import com.susukkang.fgc.common.config.MonitoringSecurityConfig;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=MonitoringEndpointTest.App.class, properties={
    "fgc.monitoring.scrape-token=test-scrape-only",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration,org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration"})
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles({"test", "monitoring"})
class MonitoringEndpointTest {
    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration
    @Import({MonitoringSecurityConfig.class, SecurityConfig.class})
    static class App {
        @Bean AtomicBoolean databaseUp() { return new AtomicBoolean(true); }
        @Bean HealthIndicator dbHealthIndicator(AtomicBoolean databaseUp) {
            return () -> databaseUp.get() ? Health.up().build() : Health.down().build();
        }
    }
    @MockitoBean GlobalExceptionHandler handler;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;
    @Autowired MockMvc mvc;
    @Autowired AtomicBoolean databaseUp;
    @Test void anonymousMetricsDenied() throws Exception { mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized()); }
    @Test void incorrectTokenDenied() throws Exception { mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized()); }
    @Test void credentialScrapesJvmAndUptime() throws Exception {
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer test-scrape-only"))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("process_uptime_seconds")));
    }
    @Test void scrapeTokenDoesNotAuthorizeBusinessApi() throws Exception {
        org.mockito.Mockito.when(handler.handleBusinessException(org.mockito.ArgumentMatchers.any())).thenReturn(org.springframework.http.ResponseEntity.status(401).build());
        org.mockito.Mockito.when(decoder.decode("test-scrape-only")).thenThrow(new org.springframework.security.oauth2.jwt.BadJwtException("Invalid application JWT"));
        mvc.perform(get("/api/v1/contracts").header("Authorization", "Bearer test-scrape-only")).andExpect(status().isUnauthorized());
    }
    @Test void healthReflectsDatabaseFailureWithoutDetails() throws Exception {
        try {
            databaseUp.set(true); mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
            databaseUp.set(false); mvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN")).andExpect(jsonPath("$.components").doesNotExist());
        } finally { databaseUp.set(true); }
    }
}
