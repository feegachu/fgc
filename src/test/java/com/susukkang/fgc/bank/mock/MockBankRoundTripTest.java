package com.susukkang.fgc.bank.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.susukkang.fgc.bank.openbanking.BankAccount;
import com.susukkang.fgc.bank.openbanking.BankToken;
import com.susukkang.fgc.bank.openbanking.BankWithdrawal;
import com.susukkang.fgc.bank.openbanking.OpenBankingClient;
import com.susukkang.fgc.bank.openbanking.OpenBankingException;
import com.susukkang.fgc.bank.openbanking.OpenBankingProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * FUN-090: 실제 은행 클라이언트(OpenBankingClient)가 HTTP 를 타고 모의 은행에 닿아 동의, 토큰, 계좌, 거래내역까지
 * 한 바퀴 도는지 확인한다. 클라이언트와 모의 은행이 같은 규격을 말하는지 보는 시험이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "fgc.openbanking.mock.enabled=true")
class MockBankRoundTripTest {

    private static final String STATE = "0123456789abcdef0123456789abcdef";
    /** 다른 테스트가 쓰지 않는 옛 날짜. 이 테스트가 넣은 줄만 지운다. */
    private static final LocalDate DAY = LocalDate.of(2001, 2, 1);

    @LocalServerPort
    private int port;
    @Autowired
    private OpenBankingProperties configured;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("동의 화면에서 동의하고 받은 코드로 토큰, 계좌, 두 쪽에 걸친 출금 내역까지 읽는다")
    void completesTheWholeFlowOverHttp() throws Exception {
        String base = "http://localhost:" + port + "/api/v1/mock-bank";
        OpenBankingClient client = new OpenBankingClient(new OpenBankingProperties(base,
                base + "/oauth/2.0/authorize", configured.clientId(), configured.clientSecret(),
                configured.redirectUri(), configured.clientUseCode(), Duration.ofSeconds(10),
                new OpenBankingProperties.Mock(true)));
        HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

        String authorizeUrl = client.authorizeUrl(STATE);
        HttpResponse<String> consent = browser.send(HttpRequest.newBuilder(URI.create(authorizeUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(consent.statusCode()).isEqualTo(200);
        assertThat(consent.body()).contains("모의 은행 계좌 조회 동의").contains("authorize/approve");

        // 동의 화면의 폼이 보내는 것과 같은 요청이다.
        HttpResponse<String> approved = browser.send(HttpRequest.newBuilder(URI.create(base + "/oauth/2.0/authorize/approve"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(authorizeUrl.substring(authorizeUrl.indexOf('?') + 1)))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(approved.statusCode()).isEqualTo(302);
        String location = approved.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith(configured.redirectUri() + "?code=").endsWith("&state=" + STATE);
        Matcher code = Pattern.compile("code=([^&]+)").matcher(location);
        assertThat(code.find()).isTrue();

        BankToken token = client.exchange(code.group(1));
        List<BankAccount> accounts = client.accounts(token);
        assertThat(accounts).singleElement().satisfies(account -> {
            assertThat(account.inquiryAgreed()).isTrue();
            assertThat(account.accountNumMasked()).contains("***");
        });

        try {
            for (int index = 0; index < 30; index++) {
                jdbc.update("""
                        INSERT INTO fgc.mock_bank_transaction (settlement_month, tran_date, tran_time, print_content, tran_amt)
                        VALUES (DATE '2000-11-01', ?, ?, ?, ?)
                        """, DAY, "10%04d".formatted(index), "FGC0011-%06d".formatted(index + 1), 1000 + index);
            }
            List<BankWithdrawal> withdrawals = client.withdrawals(token, accounts.get(0).fintechUseNum(), DAY, DAY);

            assertThat(withdrawals).hasSize(30);
            assertThat(withdrawals.get(0)).isEqualTo(new BankWithdrawal(DAY, "100000", "FGC0011-000001", new BigDecimal("1000")));
            assertThat(withdrawals.get(29)).isEqualTo(new BankWithdrawal(DAY, "100029", "FGC0011-000030", new BigDecimal("1029")));
        } finally {
            jdbc.update("DELETE FROM fgc.mock_bank_transaction WHERE settlement_month = DATE '2000-11-01'");
        }

        // 모의 은행이 발급하지 않은 토큰은 거절된다.
        assertThatThrownBy(() -> client.accounts(new BankToken("not-issued", token.userSeqNo(), 60)))
                .isInstanceOfSatisfying(OpenBankingException.class, e -> assertThat(e.code()).isEqualTo("O0002"));
        // 한 번 쓴 인가 코드는 다시 쓸 수 없다.
        assertThatThrownBy(() -> client.exchange(code.group(1)))
                .isInstanceOfSatisfying(OpenBankingException.class, e -> assertThat(e.code()).isEqualTo("O0001"));
    }
}
