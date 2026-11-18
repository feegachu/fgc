package com.susukkang.fgc.bank.openbanking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.util.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 설명 : 은행 호출이 지나가는 서버의 유일한 지점(IF-EXT-06). 금융결제원 오픈뱅킹 이용기관 API 명세(v3.3.6)의
 * 사용자 인증, 토큰 발급, 사용자정보조회, 거래내역조회를 따른다. 조회만 하고 이체는 부르지 않는다.
 *
 * - 은행은 오류도 HTTP 200 에 rsp_code 로 준다. 성공 코드가 아니면 그 코드를 담아 OpenBankingException 을 던진다.
 * - 자동 재시도는 하지 않는다. 다시 할지는 화면에서 사람이 정한다.
 * - 토큰과 핀테크이용번호는 로그에 남기지 않는다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-18
 */
@Slf4j
@Component
@EnableConfigurationProperties(OpenBankingProperties.class)
public class OpenBankingClient {

    /** 한 쪽 최대 25건이라 1,000건까지 읽는다. 그보다 많으면 끝없이 이어지는 응답으로 보고 멈춘다. */
    static final int MAX_PAGES = 40;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String SUCCESS = "A0000";
    private static final String NO_TRANSACTIONS = "A0018";
    // 금융결제원 오픈뱅킹 명세가 정한 API 주소와 항목 이름. 은행 주소(base-url)만 설정으로 받는다.
    private static final String TOKEN_API = "/oauth/2.0/token";
    private static final String USER_ME_API = "/v2.0/user/me";
    private static final String TRANSACTION_LIST_API = "/v2.0/account/transaction_list/fin_num";
    private static final String RSP_CODE = "rsp_code";
    private static final String USER_SEQ_NO = "user_seq_no";
    private static final String BAD_RESPONSE = "BAD_RESPONSE";

    private final OpenBankingProperties properties;
    private final HttpClient client;
    private final AtomicInteger serial = new AtomicInteger();

    public OpenBankingClient(OpenBankingProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 사용자의 브라우저를 보낼 은행 동의 화면 주소. state 는 돌아왔을 때 같은 요청인지 확인하는 값이다. */
    public String authorizeUrl(String state) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", properties.clientId());
        query.put("redirect_uri", properties.redirectUri());
        query.put("scope", "login inquiry");
        query.put("state", state);
        query.put("auth_type", "0");
        return properties.authorizeUrl() + "?" + encode(query);
    }

    /** 인가 코드를 접근 토큰으로 바꾼다. */
    public BankToken exchange(String code) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("code", code);
        form.put("client_id", properties.clientId());
        form.put("client_secret", properties.clientSecret());
        form.put("redirect_uri", properties.redirectUri());
        form.put("grant_type", "authorization_code");
        JsonNode json = send(TOKEN_API, HttpRequest.newBuilder(uri(TOKEN_API, Map.of()))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form), StandardCharsets.UTF_8)));
        if (!json.hasNonNull("access_token") || !json.hasNonNull(USER_SEQ_NO)) {
            throw failed(TOKEN_API, json.path(RSP_CODE).asText("TOKEN_REFUSED"));
        }
        return new BankToken(json.get("access_token").asText(), json.get(USER_SEQ_NO).asText(),
                json.path("expires_in").asLong(0));
    }

    /** 사용자가 은행에 등록한 계좌 목록. */
    public List<BankAccount> accounts(BankToken token) {
        JsonNode json = get(USER_ME_API, Map.of(USER_SEQ_NO, token.userSeqNo()), token);
        requireSuccess(USER_ME_API, json);
        List<BankAccount> accounts = new ArrayList<>();
        for (JsonNode row : list(USER_ME_API, json)) {
            accounts.add(new BankAccount(row.path("fintech_use_num").asText(), row.path("bank_name").asText(),
                    row.path("account_num_masked").asText(), row.path("account_holder_name").asText(),
                    "Y".equals(row.path("inquiry_agree_yn").asText())));
        }
        return accounts;
    }

    /** 기간 안의 출금 내역을 오래된 순으로 모두 읽는다. */
    public List<BankWithdrawal> withdrawals(BankToken token, String fintechUseNum, LocalDate from, LocalDate to) {
        List<BankWithdrawal> withdrawals = new ArrayList<>();
        String trace = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("bank_tran_id", bankTranId());
            query.put("fintech_use_num", fintechUseNum);
            query.put("inquiry_type", "O");
            query.put("inquiry_base", "D");
            query.put("from_date", DATE.format(from));
            query.put("to_date", DATE.format(to));
            query.put("sort_order", "A");
            query.put("tran_dtime", DATE_TIME.format(LocalDateTime.now(DateUtil.SEOUL_ZONE)));
            if (trace != null) {
                // 철자는 명세 원문 그대로다(befor).
                query.put("befor_inquiry_trace_info", trace);
            }
            JsonNode json = get(TRANSACTION_LIST_API, query, token);
            if (NO_TRANSACTIONS.equals(json.path(RSP_CODE).asText())) {
                return withdrawals;
            }
            requireSuccess(TRANSACTION_LIST_API, json);
            for (JsonNode row : list(TRANSACTION_LIST_API, json)) {
                withdrawals.add(withdrawal(TRANSACTION_LIST_API, row));
            }
            if (!"Y".equals(json.path("next_page_yn").asText())) {
                return withdrawals;
            }
            trace = json.path("befor_inquiry_trace_info").asText();
        }
        throw failed(TRANSACTION_LIST_API, "TOO_MANY_PAGES");
    }

    /** 성공 응답의 목록. 목록이 빠졌으면 빈 목록으로 보지 않는다. 빈 목록으로 받으면 확정 지급 전부가 이체 없음으로 남는다. */
    private static JsonNode list(String path, JsonNode json) {
        JsonNode rows = json.path("res_list");
        if (!rows.isArray()) {
            throw failed(path, BAD_RESPONSE);
        }
        return rows;
    }

    private static BankWithdrawal withdrawal(String path, JsonNode row) {
        try {
            // 명세의 표는 print_content, 예시는 printed_content 다. 둘 다 받는다.
            String printContent = row.hasNonNull("print_content")
                    ? row.get("print_content").asText() : row.path("printed_content").asText();
            return new BankWithdrawal(LocalDate.parse(row.path("tran_date").asText(), DATE),
                    row.path("tran_time").asText(), printContent.trim(), new BigDecimal(row.path("tran_amt").asText()));
        } catch (RuntimeException e) {
            throw failed(path, BAD_RESPONSE);
        }
    }

    /** 이용기관코드 10자리 + U + 이용기관 부여번호 9자리. 하루 안에서 겹치지 않게 그날의 밀리초와 일련번호로 만든다. */
    private String bankTranId() {
        long millisOfDay = LocalTime.now(DateUtil.SEOUL_ZONE).toNanoOfDay() / 1_000_000;
        String number = pad(Long.toString(millisOfDay, 36), 6)
                + pad(Integer.toString(Math.floorMod(serial.getAndIncrement(), 46_656), 36), 3);
        return properties.clientUseCode() + "U" + number.toUpperCase(Locale.ROOT);
    }

    private static String pad(String value, int length) {
        return "0".repeat(length - value.length()) + value;
    }

    private JsonNode get(String path, Map<String, String> query, BankToken token) {
        return send(path, HttpRequest.newBuilder(uri(path, query))
                .header("Authorization", "Bearer " + token.accessToken()).GET());
    }

    private JsonNode send(String path, HttpRequest.Builder request) {
        // JDK HttpClient 의 요청 제한 시간은 응답 머리까지만 본다. 본문이 멈춘 응답도 끊도록 호출 전체에 기한을 둔다.
        CompletableFuture<HttpResponse<String>> exchange = client.sendAsync(
                request.timeout(properties.timeout()).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try {
            response = exchange.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw failed(path, "TIMEOUT");
        } catch (ExecutionException e) {
            throw failed(path, e.getCause() instanceof HttpTimeoutException ? "TIMEOUT" : "IO");
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw failed(path, "INTERRUPTED");
        }
        if (response.statusCode() != 200) {
            throw failed(path, "HTTP_" + response.statusCode());
        }
        try {
            JsonNode json = JSON.readTree(response.body());
            return json == null ? JSON.createObjectNode() : json;
        } catch (IOException e) {
            throw failed(path, BAD_RESPONSE);
        }
    }

    private static void requireSuccess(String path, JsonNode json) {
        String code = json.path(RSP_CODE).asText("NO_RSP_CODE");
        if (!SUCCESS.equals(code)) {
            throw failed(path, code);
        }
    }

    private URI uri(String path, Map<String, String> query) {
        return URI.create(properties.baseUrl() + path + (query.isEmpty() ? "" : "?" + encode(query)));
    }

    private static String encode(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("&"));
    }

    private static OpenBankingException failed(String path, String code) {
        log.warn("오픈뱅킹 호출 실패 path={} code={}", path, code);
        return new OpenBankingException(code);
    }
}
