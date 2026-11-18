package com.susukkang.fgc.bank.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import com.susukkang.fgc.bank.openbanking.BankAccount;
import com.susukkang.fgc.bank.openbanking.BankToken;
import com.susukkang.fgc.bank.openbanking.BankWithdrawal;
import com.susukkang.fgc.bank.openbanking.OpenBankingClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** FUN-090 IF-API-95~99: 은행 연결과 지급 이체 대사를 실제 보안 규칙·PostgreSQL 과 함께 확인한다. 은행은 부르지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BankReconciliationApiIntegrationTest {

    private static final String LINK = "/api/v1/bank/link";
    private static final String RUNS = "/api/v1/bank-reconciliations";
    private static final BankToken TOKEN = new BankToken("ACCESS-1", "1100000001", 7_776_000L);
    private static final String FINTECH_USE_NUM = "199000000000000000000002";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FgcUserDetailsService users;
    @MockitoBean
    private OpenBankingClient client;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("정산담당자가 은행을 연결하고 확정된 달을 대사하면 결과를 돌려주고, 같은 결과를 다시 조회할 수 있다")
    void linksRunsAndReadsBack() throws Exception {
        mvc.perform(get(LINK).with(user(actor("settle01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.linked").value(false))
                .andExpect(jsonPath("$.data.connected").value(false))
                .andExpect(jsonPath("$.data.mock").value(false));

        link("settle01")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.linked").value(true))
                .andExpect(jsonPath("$.data.connected").value(true))
                .andExpect(jsonPath("$.data.bankName").value("모의은행"))
                .andExpect(jsonPath("$.data.accountNumMasked").value("100-***-***002"))
                .andExpect(jsonPath("$.data.fintechUseNum").doesNotExist());

        finalizeMonth(LocalDate.of(2026, 3, 1));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        when(client.withdrawals(eq(TOKEN), eq(FINTECH_USE_NUM), any(), any()))
                .thenReturn(List.of(new BankWithdrawal(today, "100001", "관리비", new BigDecimal("55000"))));

        mvc.perform(run("settle01", "{\"settlementMonth\":\"2026-03\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settlementMonth").value("2026-03-01"))
                .andExpect(jsonPath("$.data.createdBy").value("settle01"))
                .andExpect(jsonPath("$.data.withdrawalCount").value(1))
                .andExpect(jsonPath("$.data.matchedCount").value(0))
                .andExpect(jsonPath("$.data.unknownWithdrawalCount").value(1))
                .andExpect(jsonPath("$.data.items[0].result").value("NOT_TRANSFERRED"))
                .andExpect(jsonPath("$.data.items[0].agentCode").isString())
                .andExpect(jsonPath("$.data.items[0].referenceCode").value(org.hamcrest.Matchers.startsWith("FGC2603-")));

        // 조회 전용 역할도 결과는 볼 수 있다.
        mvc.perform(get(RUNS).param("month", "2026-03").with(user(actor("audit01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unknownWithdrawalCount").value(1))
                .andExpect(jsonPath("$.data.bankName").value("모의은행"));
    }

    @Test
    @DisplayName("대사한 적 없는 달은 빈 결과를 준다")
    void returnsNoDataBeforeTheFirstRun() throws Exception {
        mvc.perform(get(RUNS).param("month", "2026-03").with(user(actor("gaadmin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("확정되지 않은 달은 409 로 거절하고 이유를 알려 준다")
    void refusesAMonthThatIsNotFinalized() throws Exception {
        link("settle01");

        mvc.perform(run("settle01", "{\"settlementMonth\":\"2026-03\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-BANK-002"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("확정된 정산월")));
    }

    @Test
    @DisplayName("은행을 연결하지 않고 대사하면 409 로 연결을 요구한다")
    void requiresALinkBeforeRunning() throws Exception {
        finalizeMonth(LocalDate.of(2026, 3, 1));

        mvc.perform(run("settle01", "{\"settlementMonth\":\"2026-03\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-BANK-001"));
    }

    @Test
    @DisplayName("값이 빠졌거나 형식이 틀리면 입력 오류다")
    void validatesInput() throws Exception {
        mvc.perform(run("settle01", "{}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));
        mvc.perform(run("settle01", "{\"settlementMonth\":\"2026/03\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));
        mvc.perform(get(RUNS).with(user(actor("settle01")))).andExpect(status().isBadRequest());
        mvc.perform(post(LINK).with(user(actor("settle01"))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"state\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));
    }

    @Test
    @DisplayName("동의 화면 주소는 은행이 정한 값에 요청마다 다른 state 를 붙여 준다")
    void issuesADifferentStateForEveryAuthorization() throws Exception {
        when(client.authorizeUrl(anyString())).thenAnswer(call -> "/bank?state=" + call.getArgument(0));

        String first = authorizeUrl("settle01");
        String second = authorizeUrl("settle01");

        assertThat(first).matches("/bank\\?state=[0-9a-f]{32}");
        assertThat(second).isNotEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gaadmin", "audit01"})
    @DisplayName("정산 처리 역할이 아니면 은행 연결과 대사 실행을 할 수 없다")
    void onlyProcessingRolesLinkAndRun(String loginId) throws Exception {
        mvc.perform(post(LINK + "/authorize-url").with(user(actor(loginId)))).andExpect(status().isForbidden());
        mvc.perform(post(LINK).with(user(actor(loginId))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"CODE-1\",\"state\":\"0123456789abcdef0123456789abcdef\"}")).andExpect(status().isForbidden());
        mvc.perform(run(loginId, "{\"settlementMonth\":\"2026-03\"}")).andExpect(status().isForbidden());
        mvc.perform(get(LINK).with(user(actor(loginId)))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("설계사와 로그인하지 않은 사용자는 볼 수 없다")
    void deniesAgentsAndAnonymousUsers() throws Exception {
        mvc.perform(get(LINK).with(user(actor("agent01")))).andExpect(status().isForbidden());
        mvc.perform(get(RUNS).param("month", "2026-03").with(user(actor("agent01")))).andExpect(status().isForbidden());
        mvc.perform(get(LINK)).andExpect(status().isUnauthorized());
        mvc.perform(get(RUNS).param("month", "2026-03")).andExpect(status().isUnauthorized());
        mvc.perform(post(RUNS).contentType(MediaType.APPLICATION_JSON).content("{\"settlementMonth\":\"2026-03\"}"))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions link(String loginId) throws Exception {
        when(client.authorizeUrl(anyString())).thenAnswer(call -> "/bank?state=" + call.getArgument(0));
        when(client.exchange("CODE-1")).thenReturn(TOKEN);
        when(client.accounts(TOKEN)).thenReturn(List.of(
                new BankAccount(FINTECH_USE_NUM, "모의은행", "100-***-***002", "가상지에이", true)));
        String url = authorizeUrl(loginId);
        String state = url.substring(url.indexOf("state=") + "state=".length());
        return mvc.perform(post(LINK).with(user(actor(loginId))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"CODE-1\",\"state\":\"" + state + "\"}"));
    }

    private String authorizeUrl(String loginId) throws Exception {
        String body = mvc.perform(post(LINK + "/authorize-url").with(user(actor(loginId))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).path("data").path("authorizeUrl").asText();
    }

    private MockHttpServletRequestBuilder run(String loginId, String body) {
        return post(RUNS).with(user(actor(loginId))).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private UserDetails actor(String loginId) {
        return users.loadUserByUsername(loginId);
    }

    private void finalizeMonth(LocalDate month) {
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
                UPDATE fgc.validation_run SET status = 'FINALIZED', current_step = 10, finalized_at = clock_timestamp(),
                       finalized_by = (SELECT user_id FROM fgc.app_user WHERE login_id = 'gaadmin')
                 WHERE validation_run_id = ?
                """, runId);
    }
}
