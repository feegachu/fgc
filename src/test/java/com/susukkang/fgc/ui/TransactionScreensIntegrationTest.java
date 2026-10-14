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

/** #410 opt-in real HTTP/JWT, disposable PostgreSQL, and legacy/React flow comparison. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "FGC_VERIFY_TRANSACTION", matches = "true")
class TransactionScreensIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private FgcUserDetailsService users;
    @Autowired private AuthTokenService tokens;
    @LocalServerPort private int port;

    @Test
    void compareTransactionScreensAgainstRealApis() throws Exception {
        boolean baseline = "true".equals(System.getenv("FGC_TRANSACTION_BASELINE"));
        Path directory = Path.of("build/screenshots/transaction", baseline ? "baseline" : "comparison")
                .toAbsolutePath();
        Files.createDirectories(directory);
        var principal = (FgcUserDetails) users.loadUserByUsername("settle01");
        for (String screen : new String[]{"transactions", "transactions/new"}) {
            var html = mockMvc.perform(get("/" + screen).param("month", "2026-07")
                            .with(user(principal)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            Files.writeString(directory.resolve(screen.replace('/', '-') + ".html"), html);
        }
        var reader = (FgcUserDetails) users.loadUserByUsername("audit01");
        mockMvc.perform(get("/transactions/new").with(user(reader))).andExpect(status().isForbidden());
        ProcessBuilder builder = new ProcessBuilder(System.getenv().getOrDefault("FGC_NODE_BIN", "node"),
                "scripts/verify-transaction.mjs", directory.toString())
                .directory(Path.of("frontend").toFile()).inheritIO();
        builder.environment().put("FGC_TRANSACTION_SERVER", "http://localhost:" + port);
        builder.environment().put("FGC_TRANSACTION_ACCESS", tokens.login(principal).accessToken());
        builder.environment().put("FGC_TRANSACTION_READER_ACCESS", tokens.login(reader).accessToken());
        Process process = builder.start();
        boolean finished = process.waitFor(240, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("Transaction browser verification completed").isTrue();
        assertThat(process.exitValue()).as("Transaction browser verification passed").isZero();
    }
}
