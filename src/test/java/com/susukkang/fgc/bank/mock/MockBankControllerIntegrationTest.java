package com.susukkang.fgc.bank.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.bank.service.BankInquiryStarting;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** FUN-090 모의 은행: 금융결제원 오픈뱅킹 규격의 사용자 인증, 토큰, 사용자정보조회, 거래내역조회를 흉내 낸다. */
@SpringBootTest(properties = "fgc.openbanking.mock.enabled=true")
@AutoConfigureMockMvc
@Transactional
class MockBankControllerIntegrationTest {

    private static final String BASE = "/api/v1/mock-bank";
    private static final String STATE = "0123456789abcdef0123456789abcdef";
    private static final String FINTECH_USE_NUM = "199000000000000000000001";
    private static final LocalDate MARCH = LocalDate.of(2026, 3, 1);
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApplicationEventPublisher events;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("동의 화면을 보여 주고, 동의하면 인가 코드와 state 를 붙여 돌려보낸다")
    void showsTheConsentPageAndRedirectsWithACode() throws Exception {
        mvc.perform(authorize(get(BASE + "/oauth/2.0/authorize")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("모의 은행")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(STATE)));

        String location = mvc.perform(fromConsentPage(authorize(post(BASE + "/oauth/2.0/authorize/approve"))))
                .andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");

        assertThat(location).startsWith("/bank-reconciliations?code=").contains("&state=" + STATE)
                .contains("scope=login%20inquiry");
    }

    @Test
    @DisplayName("동의 화면은 인라인 스타일을 쓰지 않고 같은 출처의 스타일 파일을 쓴다")
    void servesTheConsentStylesFromTheSameOrigin() throws Exception {
        // 운영 프록시의 보안 정책이 style-src 'self' 라 인라인 스타일은 막힌다(nginx/default.conf.template).
        mvc.perform(authorize(get(BASE + "/oauth/2.0/authorize")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<style"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("style=\""))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<link rel=\"stylesheet\" href=\"" + BASE + "/consent.css\">")));

        mvc.perform(get(BASE + "/consent.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(".approve")));
    }

    @Test
    @DisplayName("취소하면 오류 값을 붙여 돌려보낸다")
    void redirectsWithAnErrorWhenTheUserDeclines() throws Exception {
        String location = mvc.perform(fromConsentPage(authorize(post(BASE + "/oauth/2.0/authorize/deny"))))
                .andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");

        assertThat(location).isEqualTo("/bank-reconciliations?error=access_denied&state=" + STATE);
    }

    @Test
    @DisplayName("등록되지 않은 이용기관이나 돌아갈 주소로는 동의 화면에 들어가지 못한다")
    void refusesUnknownClientsAndRedirectTargets() throws Exception {
        mvc.perform(authorize(get(BASE + "/oauth/2.0/authorize"), "someone-else", "/bank-reconciliations"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rsp_code").value("O0001"));
        mvc.perform(authorize(get(BASE + "/oauth/2.0/authorize"), "fgc-mock-client", "https://evil.example/steal"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rsp_code").value("O0001"));
        mvc.perform(fromConsentPage(
                        authorize(post(BASE + "/oauth/2.0/authorize/approve"), "fgc-mock-client", "https://evil.example/steal")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rsp_code").value("O0001"));
    }

    @Test
    @DisplayName("동의 화면이 준 값 없이 보낸 동의와 취소는 받지 않는다")
    void rejectsConsentFormsThatDidNotComeFromTheConsentPage() throws Exception {
        // 다른 사이트가 사용자 대신 폼을 보내는 것을 막는다. 쿠키와 숨은 입력에 같은 값이 있어야 한다.
        mvc.perform(authorize(post(BASE + "/oauth/2.0/authorize/approve"))).andExpect(status().isForbidden());
        mvc.perform(authorize(post(BASE + "/oauth/2.0/authorize/deny"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("인가 코드는 한 번만 토큰으로 바꿀 수 있고 비밀값이 틀리면 거절한다")
    void exchangesACodeOnceAndChecksTheSecret() throws Exception {
        String code = approvedCode();
        mvc.perform(token(code).param("client_secret", "wrong"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rsp_code").value("O0001"));

        // 틀린 비밀값으로 시도한 코드는 버린다.
        mvc.perform(token(code).param("client_secret", "fgc-mock-secret"))
                .andExpect(jsonPath("$.rsp_code").value("O0001"));

        String second = approvedCode();
        mvc.perform(token(second).param("client_secret", "fgc-mock-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isString())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(7_776_000))
                .andExpect(jsonPath("$.refresh_token").isString())
                .andExpect(jsonPath("$.scope").value("login inquiry"))
                .andExpect(jsonPath("$.user_seq_no").value("1100000001"));
        mvc.perform(token(second).param("client_secret", "fgc-mock-secret"))
                .andExpect(jsonPath("$.rsp_code").value("O0001"));
    }

    @Test
    @DisplayName("사용자정보조회는 토큰이 맞을 때만 가린 계좌번호와 핀테크이용번호를 준다")
    void returnsTheAccountOnlyForAValidToken() throws Exception {
        mvc.perform(get(BASE + "/v2.0/user/me").param("user_seq_no", "1100000001")
                        .header("Authorization", "Bearer not-issued"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rsp_code").value("O0002"));
        mvc.perform(get(BASE + "/v2.0/user/me").param("user_seq_no", "1100000001"))
                .andExpect(jsonPath("$.rsp_code").value("O0002"));

        mvc.perform(get(BASE + "/v2.0/user/me").param("user_seq_no", "1100000001")
                        .header("Authorization", "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rsp_code").value("A0000"))
                .andExpect(jsonPath("$.user_seq_no").value("1100000001"))
                .andExpect(jsonPath("$.res_cnt").value("1"))
                .andExpect(jsonPath("$.res_list[0].fintech_use_num").value(FINTECH_USE_NUM))
                .andExpect(jsonPath("$.res_list[0].bank_name").value("모의은행"))
                .andExpect(jsonPath("$.res_list[0].account_num_masked").value("110-***-***001"))
                .andExpect(jsonPath("$.res_list[0].account_holder_name").value("가상지에이"))
                .andExpect(jsonPath("$.res_list[0].inquiry_agree_yn").value("Y"))
                .andExpect(jsonPath("$.res_list[0].transfer_agree_yn").value("N"));
    }

    @Test
    @DisplayName("확정된 달의 확정 지급대로 이체한 출금을 만들어 주되, 세 건은 일부러 어긋나게 한다")
    void generatesTransfersForFinalizedMonthsWithThreePlantedDifferences() throws Exception {
        // 다른 테스트가 남긴 확정 실행과 섞이지 않게, 아무도 쓰지 않는 옛 날짜에 확정한 것으로 만든다.
        LocalDate today = LocalDate.of(2001, 3, 10);
        finalizeMonth(MARCH, OffsetDateTime.of(2001, 3, 10, 10, 0, 0, 0, ZoneOffset.ofHours(9)));
        List<Map<String, Object>> payees = payees(MARCH);
        assertThat(payees).hasSize(3);
        String token = accessToken();

        // 조회 요청은 원장을 만들지 않는다. 대사가 은행 조회를 시작한다고 알릴 때 그 달의 이체를 만든다.
        assertThat(transactions(token, today, today, null).path("page_record_cnt").asText()).isEqualTo("0");
        events.publishEvent(new BankInquiryStarting(MARCH));
        events.publishEvent(new BankInquiryStarting(MARCH));

        JsonNode first = transactions(token, today, today, null);
        JsonNode second = transactions(token, today, today, null);

        assertThat(first.path("rsp_code").asText()).isEqualTo("A0000");
        assertThat(first.path("bank_rsp_code").asText()).isEqualTo("000");
        assertThat(first.path("fintech_use_num").asText()).isEqualTo(FINTECH_USE_NUM);
        assertThat(first.path("next_page_yn").asText()).isEqualTo("N");
        assertThat(first.path("page_record_cnt").asText()).isEqualTo("3");
        // 두 번 알려도, 두 번 조회해도 같은 이체를 다시 만들지 않는다.
        assertThat(second.path("res_list")).isEqualTo(first.path("res_list"));

        JsonNode rows = first.path("res_list");
        // 첫 사람은 그대로, 둘째는 10,000원 덜, 마지막 사람은 보내지 않고, 참조코드 없는 출금 한 건을 섞는다.
        assertThat(rows.get(0).path("print_content").asText()).isEqualTo(reference(payees.get(0)));
        assertThat(new BigDecimal(rows.get(0).path("tran_amt").asText())).isEqualByComparingTo(net(payees.get(0)));
        assertThat(rows.get(1).path("print_content").asText()).isEqualTo(reference(payees.get(1)));
        assertThat(new BigDecimal(rows.get(1).path("tran_amt").asText()))
                .isEqualByComparingTo(net(payees.get(1)).subtract(new BigDecimal("10000")));
        assertThat(rows.get(2).path("print_content").asText()).isEqualTo("관리비");
        assertThat(rows.get(2).path("tran_amt").asText()).isEqualTo("55000");
        for (JsonNode row : rows) {
            // 명세: 금액과 건수까지 모두 문자열이다.
            assertThat(row.path("tran_date").asText()).isEqualTo(DATE.format(today));
            assertThat(row.path("tran_time").asText()).matches("\\d{6}");
            assertThat(row.path("inout_type").asText()).isEqualTo("출금");
            assertThat(row.path("tran_amt").isTextual()).isTrue();
            assertThat(row.path("after_balance_amt").asText()).matches("\\d+");
        }
        // 잔액은 출금한 만큼 줄어든다.
        assertThat(new BigDecimal(rows.get(0).path("after_balance_amt").asText())
                .subtract(new BigDecimal(rows.get(1).path("after_balance_amt").asText())))
                .isEqualByComparingTo(rows.get(1).path("tran_amt").asText());
    }

    @Test
    @DisplayName("한 쪽에 25건씩 주고 다음 쪽은 추적 값으로 이어 준다")
    void pagesTwentyFiveRowsAtATime() throws Exception {
        LocalDate day = LocalDate.of(2001, 1, 15);
        for (int index = 0; index < 30; index++) {
            jdbc.update("""
                    INSERT INTO fgc.mock_bank_transaction (settlement_month, tran_date, tran_time, print_content, tran_amt)
                    VALUES (DATE '2000-12-01', ?, ?, ?, 1000)
                    """, day, "10%04d".formatted(index), "FGC0012-%06d".formatted(index + 1));
        }
        String token = accessToken();

        JsonNode first = transactions(token, day, day, null);
        JsonNode second = transactions(token, day, day, first.path("befor_inquiry_trace_info").asText());

        assertThat(first.path("page_record_cnt").asText()).isEqualTo("25");
        assertThat(first.path("next_page_yn").asText()).isEqualTo("Y");
        assertThat(first.path("res_list")).hasSize(25);
        assertThat(first.path("res_list").get(0).path("print_content").asText()).isEqualTo("FGC0012-000001");
        assertThat(second.path("page_record_cnt").asText()).isEqualTo("5");
        assertThat(second.path("next_page_yn").asText()).isEqualTo("N");
        assertThat(second.path("res_list").get(4).path("print_content").asText()).isEqualTo("FGC0012-000030");
    }

    @Test
    @DisplayName("거래내역조회는 토큰, 핀테크이용번호, 필수 값을 확인한다")
    void validatesTheTransactionInquiry() throws Exception {
        LocalDate day = LocalDate.of(2001, 1, 15);
        String token = accessToken();

        mvc.perform(inquiry("not-issued", day, day, "M202600001U000000001", FINTECH_USE_NUM, "O"))
                .andExpect(jsonPath("$.rsp_code").value("O0002"));
        mvc.perform(inquiry(token, day, day, "M202600001U000000002", "199000000000000000009999", "O"))
                .andExpect(jsonPath("$.rsp_code").value("A0304"));
        mvc.perform(inquiry(token, day, day, "bad-id", FINTECH_USE_NUM, "O"))
                .andExpect(jsonPath("$.rsp_code").value("A0004"));
        // 조회 기간 같은 필수 값이 빠진 요청
        mvc.perform(get(BASE + "/v2.0/account/transaction_list/fin_num").header("Authorization", "Bearer " + token)
                        .param("bank_tran_id", "M202600001U000000003").param("fintech_use_num", FINTECH_USE_NUM))
                .andExpect(jsonPath("$.rsp_code").value("A0004"));
        // 입금만 물으면 이 계좌에는 없다.
        mvc.perform(inquiry(token, day, day, "M202600001U000000004", FINTECH_USE_NUM, "I"))
                .andExpect(jsonPath("$.rsp_code").value("A0000"))
                .andExpect(jsonPath("$.page_record_cnt").value("0"));
    }

    private MockHttpServletRequestBuilder authorize(MockHttpServletRequestBuilder request) {
        return authorize(request, "fgc-mock-client", "/bank-reconciliations");
    }

    private MockHttpServletRequestBuilder authorize(MockHttpServletRequestBuilder request, String clientId,
                                                    String redirectUri) {
        return request.param("response_type", "code").param("client_id", clientId)
                .param("redirect_uri", redirectUri).param("scope", "login inquiry")
                .param("state", STATE).param("auth_type", "0");
    }

    /** 브라우저처럼 동의 화면을 먼저 열어, 화면이 준 쿠키와 숨은 입력 값을 폼 요청에 싣는다. */
    private MockHttpServletRequestBuilder fromConsentPage(MockHttpServletRequestBuilder form) throws Exception {
        MvcResult page = mvc.perform(authorize(get(BASE + "/oauth/2.0/authorize"))).andReturn();
        Matcher token = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"")
                .matcher(page.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(token.find()).isTrue();
        return form.cookie(page.getResponse().getCookie("MOCK-BANK-XSRF")).param("_csrf", token.group(1));
    }

    private String approvedCode() throws Exception {
        String location = mvc.perform(fromConsentPage(authorize(post(BASE + "/oauth/2.0/authorize/approve"))))
                .andReturn().getResponse().getHeader("Location");
        Matcher matcher = Pattern.compile("code=([^&]+)").matcher(location);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private MockHttpServletRequestBuilder token(String code) {
        return post(BASE + "/oauth/2.0/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("code", code).param("client_id", "fgc-mock-client")
                .param("redirect_uri", "/bank-reconciliations").param("grant_type", "authorization_code");
    }

    private String accessToken() throws Exception {
        MvcResult result = mvc.perform(token(approvedCode()).param("client_secret", "fgc-mock-secret")).andReturn();
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("access_token").asText();
    }

    private MockHttpServletRequestBuilder inquiry(String token, LocalDate from, LocalDate to, String bankTranId,
                                                  String fintechUseNum, String inquiryType) {
        return get(BASE + "/v2.0/account/transaction_list/fin_num")
                .header("Authorization", "Bearer " + token)
                .param("bank_tran_id", bankTranId).param("fintech_use_num", fintechUseNum)
                .param("inquiry_type", inquiryType).param("inquiry_base", "D")
                .param("from_date", DATE.format(from)).param("to_date", DATE.format(to))
                .param("sort_order", "A").param("tran_dtime", "20261118101500");
    }

    private JsonNode transactions(String token, LocalDate from, LocalDate to, String trace) throws Exception {
        MockHttpServletRequestBuilder request = inquiry(token, from, to,
                "M202600001U" + "%09d".formatted(ThreadLocalRandom.current().nextInt(1, 999_999_999)),
                FINTECH_USE_NUM, "O");
        if (trace != null) {
            request.param("befor_inquiry_trace_info", trace);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String reference(Map<String, Object> payee) {
        return "FGC2603-%06d".formatted((Long) payee.get("agent_id"));
    }

    private static BigDecimal net(Map<String, Object> payee) {
        return (BigDecimal) payee.get("net");
    }

    private List<Map<String, Object>> payees(LocalDate month) {
        return jdbc.queryForList("""
                SELECT recipient_agent_id AS agent_id, SUM(amount) AS net
                  FROM fgc.commission_transaction
                 WHERE payment_stage = 'GA_TO_FC' AND status = 'CONFIRMED' AND cashflow_type = 'PAYMENT'
                   AND settlement_month = ?
                 GROUP BY recipient_agent_id ORDER BY recipient_agent_id
                """, month);
    }

    private void finalizeMonth(LocalDate month, OffsetDateTime finalizedAt) {
        Long runId = jdbc.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (?, ?, 'MONTHLY') RETURNING validation_run_id
                """, Long.class, month, ThreadLocalRandom.current().nextInt(1_000_000, 2_000_000));
        jdbc.update("""
                UPDATE fgc.validation_run SET status = 'RUNNING', current_step = 1, started_at = clock_timestamp()
                 WHERE validation_run_id = ?
                """, runId);
        jdbc.update("""
                UPDATE fgc.validation_run SET status = 'COMPLETED', current_step = 8, completed_at = clock_timestamp()
                 WHERE validation_run_id = ?
                """, runId);
        jdbc.update("""
                UPDATE fgc.validation_run SET status = 'FINALIZED', current_step = 10, finalized_at = ?,
                       finalized_by = (SELECT user_id FROM fgc.app_user WHERE login_id = 'gaadmin')
                 WHERE validation_run_id = ?
                """, finalizedAt, runId);
    }
}
