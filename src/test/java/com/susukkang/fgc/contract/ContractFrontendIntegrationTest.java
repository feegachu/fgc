package com.susukkang.fgc.contract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual browser/API contract regression against the test profile's disposable PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "FGC_CONTRACT_REAL_SERVER", matches = "1")
class ContractFrontendIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void legacyParityAndCreateUpdateFlowUseActualServer() throws Exception {
        String login = "contract-browser-test";
        String password = "contract-browser-test-only";
        jdbcTemplate.update("""
                INSERT INTO fgc.app_user (login_id, password_hash, user_name, role_id)
                VALUES (?, ?, '계약 브라우저 검증',
                    (SELECT role_id FROM fgc.app_role WHERE role_code = 'SYSTEM_ADMIN'))
                """, login, passwordEncoder.encode(password));
        ProcessBuilder builder = new ProcessBuilder(
                System.getenv().getOrDefault("FGC_NODE_BIN", "node"),
                "node_modules/@playwright/test/cli.js", "test",
                "--config=playwright.contract-server.config.ts")
                .directory(Path.of("frontend").toFile()).inheritIO();
        builder.environment().put("FGC_API_TARGET", "http://localhost:" + port);
        builder.environment().put("FGC_CONTRACT_QA_LOGIN_ID", login);
        builder.environment().put("FGC_CONTRACT_QA_PASSWORD", password);
        Process process = builder.start();
        try {
            assertThat(process.waitFor(4, TimeUnit.MINUTES)).as("실서버 계약 브라우저 검증 제한시간").isTrue();
            assertThat(process.exitValue()).as("실제 계약 API·레거시 화면 동등성").isZero();
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }
}
