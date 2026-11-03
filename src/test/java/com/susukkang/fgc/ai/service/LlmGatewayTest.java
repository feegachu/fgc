package com.susukkang.fgc.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.susukkang.fgc.ai.config.LlmProperties;
import com.susukkang.fgc.ai.entity.AiCallLog;
import com.susukkang.fgc.ai.repository.AiCallLogRepository;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.AiCallPurpose;
import com.susukkang.fgc.common.code.AiCallStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 설명 : 가짜 OpenAI 서버와 모의 저장소로 게이트웨이의 사용자별 호출 한도와 지시문 노출 차단을 DB 없이 확인한다.
 */
class LlmGatewayTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AiCallLogRepository logs = mock(AiCallLogRepository.class);
    private final List<JsonNode> sent = new CopyOnWriteArrayList<>();
    /** 받은 요청 본문으로 모델 출력 문자열(JSON)을 만든다. */
    private volatile Function<JsonNode, String> reply = request -> "{\"label\":\"정산데이터\",\"rowCount\":3}";
    private HttpServer server;

    public static class DocKind {
        public String label;
        public int rowCount;
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            sent.add(request);
            byte[] bytes = envelope(reply.apply(request)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        SecurityContextHolder.clearContext();
    }

    @Test
    void callsOverThePerUserMinuteLimitAreSkippedWithoutReachingTheProvider() {
        LlmGateway gateway = gateway();
        signIn(7L);
        for (int i = 0; i < 12; i++) {
            assertThat(gateway.call(request(), DocKind.class).rowCount).isEqualTo(3);
        }

        assertAi001(() -> gateway.call(request(), DocKind.class));

        assertThat(sent).hasSize(12);
        assertThat(lastLog().getCallStatus()).isEqualTo(AiCallStatus.FALLBACK_RULE);
        assertThat(lastLog().getErrorCode()).isEqualTo("RATE_LIMITED");

        // 한도는 사용자마다 따로 센다.
        signIn(8L);
        assertThat(gateway.call(request(), DocKind.class).rowCount).isEqualTo(3);
        assertThat(sent).hasSize(13);
    }

    @Test
    void answerRepeatingTheStartOfTheInstructionsIsDiscarded() {
        reply = request -> json(Map.of("label", request.path("instructions").asText().lines().findFirst().orElse(""), "rowCount", 3));
        LlmGateway gateway = gateway();

        assertAi001(() -> gateway.call(request(), DocKind.class));

        assertThat(sent).hasSize(1);
        assertThat(lastLog().getCallStatus()).isEqualTo(AiCallStatus.ERROR);
        assertThat(lastLog().getErrorCode()).isEqualTo("PROMPT_ECHO");
    }

    private LlmGateway gateway() {
        LlmProperties properties = new LlmProperties("test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "fgc-test-model", Duration.ofSeconds(5), 1000, 256, Duration.ofMinutes(10));
        return new LlmGateway(properties, logs, mock(PlatformTransactionManager.class));
    }

    private static LlmGateway.LlmRequest request() {
        return LlmGateway.LlmRequest.builder()
                .purpose(AiCallPurpose.INTAKE_CLASSIFY)
                .instructions("문서 서식을 분류한다.")
                .data("정산 명세 3행")
                .build();
    }

    private static void signIn(long userId) {
        FgcUserDetails user = new FgcUserDetails(
                new AppUserView(userId, "user" + userId, "unused", "테스터", "SETTLEMENT", "ACTIVE"), true, true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }

    private AiCallLog lastLog() {
        ArgumentCaptor<AiCallLog> rows = ArgumentCaptor.forClass(AiCallLog.class);
        verify(logs, atLeastOnce()).save(rows.capture());
        return rows.getValue();
    }

    private static void assertAi001(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(FgcBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.AI_001));
    }

    private String envelope(String outputText) {
        return """
                {"id":"resp_1","object":"response","created_at":1,"status":"completed","model":"fgc-test-model",
                 "output":[{"type":"message","id":"msg_1","status":"completed","role":"assistant",
                   "content":[{"type":"output_text","annotations":[],"text":%s}]}],
                 "usage":{"input_tokens":11,"output_tokens":7,"total_tokens":18,
                   "input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":0}}}
                """.formatted(json(outputText));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
