package com.susukkang.fgc.notification.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** IF-EXT-05 문자 발송 게이트웨이. 실제 사업자를 부르지 않고 같은 규격의 가짜 서버로 확인한다. */
class SmsGatewayTest {

    private static final Pattern AUTHORIZATION = Pattern.compile(
            "^HMAC-SHA256 apiKey=([^,]+), date=([^,]+), salt=([^,]+), signature=([0-9a-f]{64})$");
    private static final String ACCEPTED = """
            {"groupId":"G4V20211116140944NLZUCJOCVNOYW9Y","to":"01000001234","from":"0200001234","type":"SMS",
             "statusMessage":"정상 접수(이통사로 접수 예정) ","country":"82","messageId":"M4V20211116140944VNOQH1HURYHEWL8",
             "statusCode":"2000","accountId":"12925149"}""";

    private final List<Received> received = new ArrayList<>();
    private HttpServer server;
    private int status = 200;
    private String responseBody = ACCEPTED;
    private long delayMillis;
    private long bodyDelayMillis;

    private record Received(String method, String path, String authorization, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try {
                Thread.sleep(bodyDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // 다른 통합 테스트와 함께 돌 때도 응답이 제한 안에 들도록 넉넉히 둔다. 시간 초과 테스트만 짧은 제한을 쓴다.
    private Duration timeout = Duration.ofSeconds(10);

    private SmsGateway gateway(String apiKey, String apiSecret, String sender) {
        return new SmsGateway(new SmsProperties(apiKey, apiSecret, sender,
                "http://127.0.0.1:" + server.getAddress().getPort(), timeout));
    }

    private SmsGateway gateway() {
        return gateway("test-key", "test-secret", "0200001234");
    }

    @Test
    @DisplayName("단건 발송 규격대로 보내고 접수된 메시지 ID를 돌려준다")
    void sendsOneMessageAndReturnsTheProviderMessageId() throws Exception {
        String messageId = gateway().send("01000001234", "[FGC] 확인 문자");

        assertThat(messageId).isEqualTo("M4V20211116140944VNOQH1HURYHEWL8");
        assertThat(received).hasSize(1);
        Received request = received.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/messages/v4/send");
        JsonNode message = new ObjectMapper().readTree(request.body()).get("message");
        assertThat(message.get("to").asText()).isEqualTo("01000001234");
        assertThat(message.get("from").asText()).isEqualTo("0200001234");
        assertThat(message.get("text").asText()).isEqualTo("[FGC] 확인 문자");
        assertThat(message.get("type").asText()).isEqualTo("SMS");
    }

    @Test
    @DisplayName("인증 헤더의 서명은 비밀키로 date와 salt를 이어 붙여 만든 HMAC-SHA256 16진수다")
    void signsEachRequestWithDateAndSalt() throws Exception {
        SmsGateway gateway = gateway();
        gateway.send("01000001234", "첫 번째");
        gateway.send("01000001234", "두 번째");

        Matcher first = AUTHORIZATION.matcher(received.get(0).authorization());
        Matcher second = AUTHORIZATION.matcher(received.get(1).authorization());
        assertThat(first.matches()).isTrue();
        assertThat(second.matches()).isTrue();
        assertThat(first.group(1)).isEqualTo("test-key");
        assertThat(Duration.between(Instant.parse(first.group(2)), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(first.group(3).length()).isBetween(12, 64);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertThat(first.group(4)).isEqualTo(HexFormat.of().formatHex(
                mac.doFinal((first.group(2) + first.group(3)).getBytes(StandardCharsets.UTF_8))));
        // 같은 서명은 15분 안에 다시 쓸 수 없으므로 요청마다 salt가 달라야 한다.
        assertThat(second.group(3)).isNotEqualTo(first.group(3));
    }

    @Test
    @DisplayName("키나 발신번호가 비어 있으면 밖으로 보내지 않고 설정 없음으로 끝낸다")
    void doesNotCallTheProviderWithoutCredentials() {
        for (SmsGateway gateway : List.of(gateway("", "test-secret", "0200001234"),
                gateway("test-key", " ", "0200001234"), gateway("test-key", "test-secret", null))) {
            assertThat(gateway.isConfigured()).isFalse();
            assertThatThrownBy(() -> gateway.send("01000001234", "보내지 않는다"))
                    .isInstanceOfSatisfying(SmsSendException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("NO_API_KEY");
                        assertThat(failure.retryable()).isFalse();
                    });
        }
        assertThat(received).isEmpty();
        assertThat(gateway().isConfigured()).isTrue();
    }

    @Test
    @DisplayName("인증과 요청 오류는 사업자 오류 코드로 남기고 다시 보내지 않는다")
    void doesNotRetryClientErrors() {
        status = 403;
        responseBody = """
                {"errorCode":"SignatureDoesNotMatch","errorMessage":"Signature 불일치"}""";

        assertThatThrownBy(() -> gateway().send("01000001234", "문자"))
                .isInstanceOfSatisfying(SmsSendException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("SignatureDoesNotMatch");
                    assertThat(failure.retryable()).isFalse();
                });
    }

    @Test
    @DisplayName("사업자 서버 오류는 다시 보낼 수 있는 실패로 남긴다")
    void retriesProviderServerErrors() {
        status = 503;
        responseBody = "unavailable";

        assertThatThrownBy(() -> gateway().send("01000001234", "문자"))
                .isInstanceOfSatisfying(SmsSendException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("HTTP_503");
                    assertThat(failure.retryable()).isTrue();
                });
    }

    @Test
    @DisplayName("응답이 제한 시간을 넘기면 다시 보낼 수 있는 실패로 남긴다")
    void retriesTimeouts() {
        timeout = Duration.ofMillis(300);
        delayMillis = 1_500;

        assertThatThrownBy(() -> gateway().send("01000001234", "문자"))
                .isInstanceOfSatisfying(SmsSendException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("TIMEOUT");
                    assertThat(failure.retryable()).isTrue();
                });
    }

    @Test
    @DisplayName("응답 머리만 오고 본문이 오지 않아도 제한 시간이 지나면 다시 보낼 수 있는 실패로 남긴다")
    void retriesWhenTheBodyStalls() {
        timeout = Duration.ofMillis(300);
        bodyDelayMillis = 1_500;

        long started = System.nanoTime();
        assertThatThrownBy(() -> gateway().send("01000001234", "문자"))
                .isInstanceOfSatisfying(SmsSendException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("TIMEOUT");
                    assertThat(failure.retryable()).isTrue();
                });
        // JDK HttpClient 의 요청 제한 시간은 응답 머리까지만 본다. 본문까지 기다리면 1.5초가 걸린다.
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_200));
    }

    @Test
    @DisplayName("200이어도 접수 코드가 아니면 실패로 남긴다")
    void treatsANonAcceptedStatusCodeAsFailure() {
        responseBody = """
                {"statusCode":"3058","statusMessage":"실패","to":"01000001234"}""";

        assertThatThrownBy(() -> gateway().send("01000001234", "문자"))
                .isInstanceOfSatisfying(SmsSendException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("STATUS_3058");
                    assertThat(failure.retryable()).isFalse();
                });
    }
}
