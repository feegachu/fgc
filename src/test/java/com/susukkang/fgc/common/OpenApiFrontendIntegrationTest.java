package com.susukkang.fgc.common;

import com.susukkang.fgc.auth.service.AuthTokenService;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #402: authenticated OpenAPI export and actual HTTP frontend/JWT integration.
 * Opt-in only; uses the test profile's disposable PostgreSQL, never the developer database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "FGC_EXPORT_OPENAPI", matches = "true")
class OpenApiFrontendIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @LocalServerPort private int port;
    @Autowired private AuthTokenService tokenService;
    @Autowired private FgcUserDetailsService userDetails;
    @Autowired private JwtEncoder encoder;
    @Autowired private JwtDecoder decoder;
    @Value("${fgc.demo-month}") private String demoMonth;

    @Test
    void exportAuthenticatedSchemaAndVerifyFrontendAgainstServer() throws Exception {
        String schema = mockMvc.perform(get("/v3/api-docs").with(user("schema-export").roles("SYSTEM_ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ObjectNode root = (ObjectNode) objectMapper.readTree(schema);
        JsonNode paths = root.get("paths");
        assertThat(paths.has("/api/v1/auth/login")).isTrue();
        assertThat(paths.has("/api/v1/auth/refresh")).isTrue();
        assertThat(paths.has("/api/v1/contracts")).isTrue();
        // Origin is environment-specific, not part of the API type contract.
        root.putArray("servers").addObject().put("url", "/");
        Path output = Path.of("frontend/openapi/schema.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(canonical(root)) + "\n");

        var tokens = tokenService.login((FgcUserDetails) userDetails.loadUserByUsername("audit01"));
        var claims = JwtClaimsSet.builder().claims(values -> values.putAll(decoder.decode(tokens.accessToken()).getClaims()))
                .issuedAt(Instant.now().minusSeconds(300)).expiresAt(Instant.now().minusSeconds(120)).build();
        String expired = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        ProcessBuilder builder = new ProcessBuilder(
                System.getenv().getOrDefault("FGC_NODE_BIN", "node"),
                "node_modules/vitest/vitest.mjs", "run", "src/lib/api/server.test.ts")
                .directory(Path.of("frontend").toFile()).inheritIO();
        builder.environment().put("FGC_INTEGRATION_URL", "http://localhost:" + port);
        builder.environment().put("FGC_EXPIRED_ACCESS", expired);
        builder.environment().put("FGC_EXPIRED_REFRESH", tokens.refreshToken());
        builder.environment().put("FGC_EXPECTED_DEMO_MONTH", demoMonth);
        Process process = builder.start();
        boolean finished = process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("Frontend HTTP integration completed").isTrue();
        assertThat(process.exitValue()).as("Frontend HTTP integration passed").isZero();
    }

    /** springdoc's accessor-property discovery order can vary across JVMs. Keep exports reproducible. */
    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            var names = new java.util.TreeSet<String>();
            value.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            var result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

}
