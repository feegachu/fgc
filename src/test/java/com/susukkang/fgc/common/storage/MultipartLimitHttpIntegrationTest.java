package com.susukkang.fgc.common.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** MockMvc multipart는 서블릿 파서를 거치지 않으므로 실제 Tomcat HTTP로 조기 오류를 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_files_http_637?currentSchema=fgc",
        "spring.servlet.multipart.max-file-size=32B", "spring.servlet.multipart.max-request-size=2KB",
        "fgc.storage.max-file-bytes=32"
})
@DirtiesContext
class MultipartLimitHttpIntegrationTest {
    private static final String PROBE = "/api/v1/_upload-size-probe";
    private static final AtomicInteger CONTROLLER_CALLS = new AtomicInteger();
    @LocalServerPort int port;
    @BeforeEach void reset() { CONTROLLER_CALLS.set(0); }

    @ParameterizedTest @ValueSource(ints = {512, 4096})
    void earlyFileAndRequestLimitReturnIntakeEnvelopeBeforeController(int size) throws Exception {
        String boundary = "fgc637Boundary";
        String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n" + "a".repeat(size) + "\r\n--" + boundary + "--\r\n";
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PROBE))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.getBytes(StandardCharsets.UTF_8))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
            assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/json");
            assertThat(response.body()).contains("FGC-INTK-001").doesNotContain("{max}", "(0MB)");
            assertThat(CONTROLLER_CALLS).hasValue(0);
        }
    }

    @TestConfiguration static class ProbeConfig {
        // 테스트 전용 경로만 공개한다. 운영 체인의 권한·CSRF를 바꾸지 않는다.
        @Bean @Order(0) SecurityFilterChain uploadProbeSecurity(HttpSecurity http) throws Exception {
            return http.securityMatcher(PROBE).csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }
        @Bean UploadProbe uploadProbe() { return new UploadProbe(); }
    }
    @org.springframework.boot.test.context.TestComponent
    @RestController static class UploadProbe {
        @PostMapping(PROBE) String receive(@RequestPart("file") MultipartFile file) {
            CONTROLLER_CALLS.incrementAndGet();
            return "unexpected";
        }
    }
}
