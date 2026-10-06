package com.susukkang.fgc.ui;

import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Opt-in visual evidence against the real Thymeleaf renderer using the disposable test DB. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "FGC_VERIFY_SHELL", matches = "true")
class AppShellScreenshotIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private FgcUserDetailsService users;

    @Test
    void captureLegacyAndReactShellAtSameViewport() throws Exception {
        Path directory = Path.of("build/screenshots/app-shell").toAbsolutePath();
        Files.createDirectories(directory);
        String html = mockMvc.perform(get("/contracts").param("month", "2026-07")
                        .with(user(users.loadUserByUsername("settle01"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Files.writeString(directory.resolve("legacy.html"), html);
        Process process = new ProcessBuilder(System.getenv().getOrDefault("FGC_NODE_BIN", "node"),
                "scripts/verify-shell.mjs", directory.toString())
                .directory(Path.of("frontend").toFile()).inheritIO().start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("Shell screenshot comparison completed").isTrue();
        assertThat(process.exitValue()).as("Shell screenshot comparison passed").isZero();
    }
}
