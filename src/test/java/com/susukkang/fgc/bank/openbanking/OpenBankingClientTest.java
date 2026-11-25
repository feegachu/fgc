package com.susukkang.fgc.bank.openbanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** FGC-FUN-090, IF-EXT-06 오픈뱅킹 규격 클라이언트. 실제 은행을 부르지 않고 같은 규격의 가짜 서버로 확인한다. */
class OpenBankingClientTest {

    private static final String TOKEN = """
            {"access_token":"ACCESS-1","token_type":"Bearer","expires_in":7776000,
             "refresh_token":"REFRESH-1","scope":"login inquiry","user_seq_no":"1100000001"}""";
    private static final String USER = """
            {"api_tran_id":"T1","api_tran_dtm":"20261118101500123","rsp_code":"A0000","rsp_message":"",
             "user_seq_no":"1100000001","user_ci":"CI","user_name":"가상지에이","res_cnt":"2",
             "res_list":[
               {"fintech_use_num":"199000000000000000000001","account_alias":"잔돈","bank_code_std":"097",
                "bank_name":"모의은행","account_num_masked":"100-***-***001","account_holder_name":"가상지에이",
                "account_holder_type":"P","account_type":"1","inquiry_agree_yn":"N","transfer_agree_yn":"N"},
               {"fintech_use_num":"199000000000000000000002","account_alias":"수수료 지급","bank_code_std":"097",
                "bank_name":"모의은행","account_num_masked":"100-***-***002","account_holder_name":"가상지에이",
                "account_holder_type":"P","account_type":"1","inquiry_agree_yn":"Y","transfer_agree_yn":"N"}]}""";

    private final List<Received> received = new ArrayList<>();
    private final Map<String, Deque<String>> responses = new HashMap<>();
    private HttpServer server;
    // FGC-FUN-090 호출 제한 시간(응답 본문까지 5초)을 확인하려고 느린 은행을 흉내 내는 문.
    // 닫아 두면 가짜 은행이 그 자리에서 테스트가 끝날 때까지 답하지 않는다.
    private volatile CountDownLatch beforeHeaders = new CountDownLatch(0);
    private volatile CountDownLatch beforeBody = new CountDownLatch(0);
    private volatile boolean headersSent;
    // 다른 통합 테스트와 함께 돌 때도 응답이 제한 안에 들도록 넉넉히 둔다. 시간 초과 테스트만 짧은 제한을 쓴다.
    private Duration timeout = Duration.ofSeconds(10);

    private record Received(String method, String path, Map<String, String> query, String authorization,
                            String contentType, Map<String, String> form) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestMethod(), path,
                    parse(exchange.getRequestURI().getRawQuery()),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), parse(body)));
            waitFor(beforeHeaders);
            Deque<String> queue = responses.get(path);
            // 명세: 오류도 HTTP 200 에 rsp_code 로 준다.
            byte[] response = (queue == null || queue.isEmpty() ? "{}" : queue.size() == 1 ? queue.peek() : queue.poll())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            headersSent = true;
            waitFor(beforeBody);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        // 멈춰 세운 응답을 먼저 풀어야 서버가 멈춘다.
        beforeHeaders.countDown();
        beforeBody.countDown();
        server.stop(0);
    }

    private static void waitFor(CountDownLatch gate) {
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("사용자 인증 주소에 명세의 필수 값을 담는다")
    void buildsTheAuthorizeUrlWithRequiredParameters() {
        String url = client().authorizeUrl("0123456789abcdef0123456789abcdef");

        assertThat(url).startsWith("/bank/authorize?");
        Map<String, String> query = parse(url.substring(url.indexOf('?') + 1));
        assertThat(query).containsEntry("response_type", "code").containsEntry("client_id", "client-1")
                .containsEntry("redirect_uri", "/bank-reconciliations").containsEntry("scope", "login inquiry")
                .containsEntry("state", "0123456789abcdef0123456789abcdef").containsEntry("auth_type", "0");
    }

    @Test
    @DisplayName("인가 코드를 토큰으로 바꿀 때 폼 형식으로 보내고 토큰과 사용자 일련번호를 받는다")
    void exchangesTheAuthorizationCodeForAToken() {
        respond("/oauth/2.0/token", TOKEN);

        BankToken token = client().exchange("CODE-1");

        assertThat(token.accessToken()).isEqualTo("ACCESS-1");
        assertThat(token.userSeqNo()).isEqualTo("1100000001");
        assertThat(token.expiresInSeconds()).isEqualTo(7_776_000L);
        Received request = received.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.contentType()).startsWith("application/x-www-form-urlencoded");
        assertThat(request.form()).containsEntry("code", "CODE-1").containsEntry("client_id", "client-1")
                .containsEntry("client_secret", "secret-1").containsEntry("redirect_uri", "/bank-reconciliations")
                .containsEntry("grant_type", "authorization_code");
    }

    @Test
    @DisplayName("토큰 발급이 거절되면 응답 코드를 담아 실패로 알린다")
    void reportsTheResponseCodeWhenTheTokenIsRefused() {
        respond("/oauth/2.0/token", """
                {"rsp_code":"O0001","rsp_message":"인증요청 거부-인증 파라미터 오류 ([3000201])"}""");

        assertThatThrownBy(() -> client().exchange("WRONG"))
                .isInstanceOfSatisfying(OpenBankingException.class, e -> assertThat(e.code()).isEqualTo("O0001"));
    }

    @Test
    @DisplayName("사용자 계좌 목록을 Bearer 토큰으로 조회한다")
    void listsTheUsersAccounts() {
        respond("/v2.0/user/me", USER);

        List<BankAccount> accounts = client().accounts(token());

        assertThat(accounts).hasSize(2);
        assertThat(accounts.get(1)).isEqualTo(new BankAccount("199000000000000000000002", "모의은행",
                "100-***-***002", "가상지에이", true));
        assertThat(accounts.get(0).inquiryAgreed()).isFalse();
        Received request = received.get(0);
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.authorization()).isEqualTo("Bearer ACCESS-1");
        assertThat(request.query()).containsEntry("user_seq_no", "1100000001");
    }

    @Test
    @DisplayName("출금 내역을 명세의 필수 값으로 조회하고 다음 쪽이 없을 때까지 이어 받는다")
    void readsWithdrawalsAcrossPages() {
        respond("/v2.0/account/transaction_list/fin_num", page("Y", "TRACE-1", """
                {"tran_date":"20261118","tran_time":"100001","inout_type":"출금","tran_type":"대체",
                 "print_content":"FGC2607-000001","tran_amt":"890000","after_balance_amt":"99110000","branch_name":"본점"}"""),
                page("N", "", """
                {"tran_date":"20261118","tran_time":"100002","inout_type":"출금","tran_type":"대체",
                 "printed_content":"관리비","tran_amt":"55000","after_balance_amt":"99055000","branch_name":"본점"}"""));

        List<BankWithdrawal> withdrawals = client().withdrawals(token(), "199000000000000000000002",
                LocalDate.of(2026, 11, 18), LocalDate.of(2026, 12, 19));

        // 명세의 표는 print_content, 예시는 printed_content 라 둘 다 받는다.
        assertThat(withdrawals).containsExactly(
                new BankWithdrawal(LocalDate.of(2026, 11, 18), "100001", "FGC2607-000001", new BigDecimal("890000")),
                new BankWithdrawal(LocalDate.of(2026, 11, 18), "100002", "관리비", new BigDecimal("55000")));
        assertThat(received).hasSize(2);
        Map<String, String> first = received.get(0).query();
        assertThat(received.get(0).authorization()).isEqualTo("Bearer ACCESS-1");
        assertThat(first).containsEntry("fintech_use_num", "199000000000000000000002")
                .containsEntry("inquiry_type", "O").containsEntry("inquiry_base", "D")
                .containsEntry("from_date", "20261118").containsEntry("to_date", "20261219")
                .containsEntry("sort_order", "A").doesNotContainKey("befor_inquiry_trace_info");
        assertThat(first.get("tran_dtime")).matches("\\d{14}");
        // 거래고유번호: 이용기관코드 10자리 + U + 이용기관 부여번호 9자리, 요청마다 달라야 한다.
        assertThat(first.get("bank_tran_id")).matches("M202600001U[A-Z0-9]{9}");
        Map<String, String> second = received.get(1).query();
        assertThat(second).containsEntry("befor_inquiry_trace_info", "TRACE-1");
        assertThat(second.get("bank_tran_id")).matches("M202600001U[A-Z0-9]{9}").isNotEqualTo(first.get("bank_tran_id"));
    }

    @Test
    @DisplayName("거래내역이 없다는 응답은 빈 목록으로 받는다")
    void treatsNoTransactionsAsAnEmptyList() {
        respond("/v2.0/account/transaction_list/fin_num", """
                {"rsp_code":"A0018","rsp_message":"거래내역이 존재하지 않습니다."}""");

        assertThat(client().withdrawals(token(), "199000000000000000000002",
                LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 18))).isEmpty();
    }

    @Test
    @DisplayName("은행이 실패 코드를 주면 그 코드를 담아 실패로 알린다")
    void reportsBankFailures() {
        respond("/v2.0/account/transaction_list/fin_num", """
                {"rsp_code":"A0002","rsp_message":"참가기관 에러","bank_rsp_code":"111","bank_rsp_message":"기관 장애"}""");

        assertThatThrownBy(() -> client().withdrawals(token(), "199000000000000000000002",
                LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 18)))
                .isInstanceOfSatisfying(OpenBankingException.class, e -> assertThat(e.code()).isEqualTo("A0002"));
    }

    @Test
    @DisplayName("성공 코드인데 목록이 빠진 응답은 빈 목록으로 보지 않고 잘못된 응답으로 알린다")
    void rejectsSuccessResponsesWithoutTheList() {
        // 빈 목록으로 받으면 확정 지급 전부가 "이체 없음"으로 저장된다.
        respond("/v2.0/account/transaction_list/fin_num", """
                {"api_tran_id":"T1","rsp_code":"A0000","rsp_message":"","next_page_yn":"N"}""");
        respond("/v2.0/user/me", """
                {"api_tran_id":"T1","rsp_code":"A0000","rsp_message":"","user_seq_no":"1100000001","res_list":null}""");
        LocalDate day = LocalDate.of(2026, 11, 18);

        assertThatThrownBy(() -> client().withdrawals(token(), "199000000000000000000002", day, day))
                .isInstanceOfSatisfying(OpenBankingException.class, e -> assertThat(e.code()).isEqualTo("BAD_RESPONSE"));
        assertThatThrownBy(() -> client().accounts(token()))
                .isInstanceOfSatisfying(OpenBankingException.class, e -> assertThat(e.code()).isEqualTo("BAD_RESPONSE"));
    }

    @Test
    @DisplayName("다음 쪽 여부 또는 커서가 빠진 응답으로 대사를 끝내지 않는다")
    void rejectsIncompletePagingInformation() {
        String path = "/v2.0/account/transaction_list/fin_num";
        respond(path, "{\"rsp_code\":\"A0000\",\"res_list\":[]}",
                "{\"rsp_code\":\"A0000\",\"next_page_yn\":\"Y\",\"res_list\":[]}");
        LocalDate day = LocalDate.of(2026, 11, 18);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client().withdrawals(token(), "199000000000000000000002", day, day))
                    .isInstanceOfSatisfying(OpenBankingException.class, e -> assertThat(e.code()).isEqualTo("BAD_RESPONSE"));
        }
    }

    @Test
    @DisplayName("쪽이 끝없이 이어지면 멈추고 실패로 알린다")
    void stopsWhenPagesNeverEnd() {
        respond("/v2.0/account/transaction_list/fin_num", page("Y", "TRACE-LOOP", """
                {"tran_date":"20261118","tran_time":"100001","inout_type":"출금","tran_type":"대체",
                 "print_content":"FGC2607-000001","tran_amt":"1","after_balance_amt":"1","branch_name":"본점"}"""));

        assertThatThrownBy(() -> client().withdrawals(token(), "199000000000000000000002",
                LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 18)))
                .isInstanceOfSatisfying(OpenBankingException.class, e -> assertThat(e.code()).isEqualTo("TOO_MANY_PAGES"));
        assertThat(received).hasSize(OpenBankingClient.MAX_PAGES);
    }

    // 기한이 사라져 끝없이 기다리는 회귀만 잡는 제한이다. 첫 호출 준비가 느린 환경에서도 걸리지 않게 넉넉히 둔다.
    @Test
    @Timeout(60)
    @DisplayName("FGC-FUN-090 제한 시간 안에 응답이 없으면 시간 초과로 알린다")
    void reportsTimeouts() {
        respond("/v2.0/user/me", USER);
        beforeHeaders = new CountDownLatch(1);
        timeout = Duration.ofMillis(300);

        assertThatThrownBy(() -> client().accounts(token()))
                .isInstanceOfSatisfying(OpenBankingException.class, e -> assertThat(e.code()).isEqualTo("TIMEOUT"));
    }

    @Test
    @Timeout(60)
    @DisplayName("FGC-FUN-090 응답 머리만 오고 본문이 오지 않아도 제한 시간이 지나면 시간 초과로 알린다")
    void reportsTimeoutsWhenTheBodyStalls() {
        respond("/v2.0/user/me", USER);
        // 첫 호출의 준비 시간(클래스 적재, 연결)이 아래 제한 시간에 섞이지 않게 넉넉한 제한으로 한 번 불러 둔다.
        client().accounts(token());
        beforeBody = new CountDownLatch(1);
        headersSent = false;
        // 머리가 제한 안에 넉넉히 들어오게 1초를 준다. 머리가 늦어 난 시간 초과와 섞이지 않게 한다.
        timeout = Duration.ofSeconds(1);
        OpenBankingClient client = client();

        assertThatThrownBy(() -> client.accounts(token()))
                .isInstanceOfSatisfying(OpenBankingException.class, e -> assertThat(e.code()).isEqualTo("TIMEOUT"));
        // JDK HttpClient 의 요청 제한 시간은 응답 머리까지만 본다. 머리는 왔고 본문은 이 테스트가 끝날 때까지 오지 않는다.
        assertThat(headersSent).isTrue();
    }

    private OpenBankingClient client() {
        return new OpenBankingClient(new OpenBankingProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), "/bank/authorize", "client-1", "secret-1",
                "/bank-reconciliations", "M202600001", timeout, new OpenBankingProperties.Mock(false)));
    }

    private static BankToken token() {
        return new BankToken("ACCESS-1", "1100000001", 7_776_000L);
    }

    private void respond(String path, String... bodies) {
        responses.put(path, new ArrayDeque<>(List.of(bodies)));
    }

    private static String page(String nextPageYn, String trace, String row) {
        return """
                {"api_tran_id":"T1","api_tran_dtm":"20261118101500123","rsp_code":"A0000","rsp_message":"",
                 "bank_tran_id":"M202600001U000000001","bank_tran_date":"20261118","bank_code_tran":"097",
                 "bank_rsp_code":"000","bank_rsp_message":"","bank_name":"모의은행",
                 "fintech_use_num":"199000000000000000000002","balance_amt":"99055000","page_record_cnt":"1",
                 "next_page_yn":"%s","befor_inquiry_trace_info":"%s","res_list":[%s]}""".formatted(nextPageYn, trace, row);
    }

    private static Map<String, String> parse(String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            int at = pair.indexOf('=');
            if (at > 0) {
                values.put(URLDecoder.decode(pair.substring(0, at), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }
}
