package com.susukkang.fgc.ui;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.AuthTokenService;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** #412 opt-in: disposable PostgreSQL, real JWT/API, legacy/React comparison and manual recheck. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "FGC_VERIFY_ARBITRAGE", matches = "true")
class ArbitrageScreensIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private FgcUserDetailsService users;
    @Autowired private AuthTokenService tokens;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @LocalServerPort private int port;

    @Test
    void verifyArbitrageScreensAndRecheck() throws Exception {
        Path directory = Path.of("build/screenshots/arbitrage").toAbsolutePath();
        Files.createDirectories(directory);
        var principal = (FgcUserDetails) users.loadUserByUsername("settle01");
        var html = mockMvc.perform(get("/arbitrage-checks").param("month", "2026-07").with(user(principal)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Files.writeString(directory.resolve("arbitrage.html"), html);
        var token = tokens.login(principal);
        ProcessBuilder builder = new ProcessBuilder(System.getenv().getOrDefault("FGC_NODE_BIN", "node"),
                "scripts/verify-arbitrage.mjs", directory.toString())
                .directory(Path.of("frontend").toFile()).inheritIO();
        builder.environment().put("FGC_ARB_SERVER", "http://localhost:" + port);
        builder.environment().put("FGC_ARB_ACCESS", token.accessToken());
        builder.environment().put("FGC_ARB_CONTRACT", String.valueOf(jdbc.queryForObject(
                "select contract_id from fgc.insurance_contract where contract_no = 'FGC-FGL02-202601-0001'", Long.class)));
        Process process = builder.start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("Arbitrage browser verification completed").isTrue();
        assertThat(process.exitValue()).as("Arbitrage browser verification passed").isZero();
    }
}
