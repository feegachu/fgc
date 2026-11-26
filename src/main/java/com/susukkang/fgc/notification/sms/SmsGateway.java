package com.susukkang.fgc.notification.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 설명 : 외부 문자 발송(솔라피 단건 발송 API) 호출이 지나가는 서버의 유일한 지점(IF-EXT-05).
 *
 * - 키·비밀키·발신번호는 서버 설정(fgc.sms.* ← 환경변수)으로만 받는다. 비어 있으면 외부로 보내지 않고 NO_API_KEY 로 끝낸다.
 * - 요청마다 date + salt 를 비밀키로 서명한다(HMAC-SHA256, 16진수). 같은 서명은 다시 쓸 수 없어 salt 를 매번 새로 만든다.
 * - 자동 재시도는 하지 않는다. 다시 보낼 수 있는 실패(시간 초과·연결 오류·429·5xx)인지만 알려 주고 재시도는 호출자가 정한다.
 * - 수신번호와 본문은 로그에 남기지 않는다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-17
 */
@Slf4j
@Component
@EnableConfigurationProperties(SmsProperties.class)
public class SmsGateway {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 사업자가 정상 접수했을 때의 statusCode. */
    private static final String ACCEPTED = "2000";
    /** closing_notification.error_code varchar(60) */
    private static final int CODE_MAX_LENGTH = 60;

    private final SmsProperties properties;
    private final HttpClient client;

    public SmsGateway(SmsProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        if (!isConfigured()) {
            log.warn("fgc.sms 의 키·비밀키·발신번호가 비어 있어 문자 발송을 끈 채로 기동합니다(알림 이력만 남김).");
        }
    }

    public boolean isConfigured() {
        return hasText(properties.apiKey()) && hasText(properties.apiSecret()) && hasText(properties.sender());
    }

    /**
     * 설명 : 문자 1건을 보내고 사업자가 준 메시지 ID를 돌려준다. 실패하면 사유를 담은 SmsSendException 을 던진다.
     *
     * @author yslee
     * @version 1.0
     * @since 2026-11-17
     */
    public String send(String to, String text) {
        if (!isConfigured()) {
            throw new SmsSendException("NO_API_KEY", false);
        }
        String body;
        try {
            body = JSON.writeValueAsString(Map.of("message",
                    Map.of("to", to, "from", properties.sender(), "text", text, "type", "SMS")));
        } catch (IOException e) {
            throw failed("IO", true);
        }
        // JDK HttpClient 의 요청 제한 시간은 응답 머리까지만 본다. 본문이 멈춘 응답도 끊도록 호출 전체에 기한을 둔다.
        CompletableFuture<HttpResponse<String>> exchange = client.sendAsync(
                HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/messages/v4/send"))
                        .timeout(properties.timeout())
                        .header("Authorization", authorization())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try {
            response = exchange.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw failed("TIMEOUT", true);
        } catch (ExecutionException e) {
            throw failed(e.getCause() instanceof HttpTimeoutException ? "TIMEOUT" : "IO", true);
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw failed("INTERRUPTED", true);
        }
        JsonNode json = parse(response.body());
        if (response.statusCode() != 200) {
            boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
            throw failed(json.path("errorCode").asText("HTTP_" + response.statusCode()), retryable);
        }
        if (!ACCEPTED.equals(json.path("statusCode").asText()) || !json.hasNonNull("messageId")) {
            throw failed("STATUS_" + json.path("statusCode").asText("UNKNOWN"), false);
        }
        return json.get("messageId").asText();
    }

    private String authorization() {
        String date = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        String salt = HexFormat.of().formatHex(random);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.apiSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = HexFormat.of().formatHex(mac.doFinal((date + salt).getBytes(StandardCharsets.UTF_8)));
            return "HMAC-SHA256 apiKey=" + properties.apiKey() + ", date=" + date + ", salt=" + salt
                    + ", signature=" + signature;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 서명을 만들 수 없습니다.", e);
        }
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode json = JSON.readTree(body);
            return json == null ? JSON.createObjectNode() : json;
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    private static SmsSendException failed(String code, boolean retryable) {
        log.warn("문자 발송 실패 code={} retryable={}", code, retryable);
        return new SmsSendException(code.length() <= CODE_MAX_LENGTH ? code : code.substring(0, CODE_MAX_LENGTH), retryable);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
