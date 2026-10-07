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

/** #406 opt-in real HTTP/JWT + disposable PostgreSQL + legacy/React browser comparison. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "FGC_VERIFY_REFERENCE", matches = "true")
class ReferenceScreensIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private FgcUserDetailsService users;
    @Autowired private AuthTokenService tokens;
    @LocalServerPort private int port;

    @Test
    void compareReferenceScreensAgainstRealApis() throws Exception {
        Path directory = Path.of("build/screenshots/reference").toAbsolutePath();
        Files.createDirectories(directory);
        var principal = (FgcUserDetails) users.loadUserByUsername("settle01");
        for (String screen : new String[]{"base", "policies"}) {
            var html = mockMvc.perform(get("/" + screen).param("month", "2026-07")
                            .param("asOf", "2026-07-01").with(user(principal)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            Files.writeString(directory.resolve(screen + ".html"), html);
        }
        var token = tokens.login(principal);
        ProcessBuilder builder = new ProcessBuilder(System.getenv().getOrDefault("FGC_NODE_BIN", "node"),
                "scripts/verify-reference.mjs", directory.toString())
                .directory(Path.of("frontend").toFile()).inheritIO();
        builder.environment().put("FGC_REFERENCE_SERVER", "http://localhost:" + port);
        builder.environment().put("FGC_REFERENCE_ACCESS", token.accessToken());
        Process process = builder.start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("Reference browser verification completed").isTrue();
        assertThat(process.exitValue()).as("Reference browser verification passed").isZero();
    }
}
