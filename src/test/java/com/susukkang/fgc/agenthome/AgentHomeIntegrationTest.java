package com.susukkang.fgc.agenthome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.AuthTokenService;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

/** FUN-086: 실제 PostgreSQL·JWT 로 홈 집계(수동 SQL 대조)와 확인 기록의 멱등·최신 상태·본인 범위를 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AgentHomeIntegrationTest {
    private static final String SCOPED = """
            FROM fgc.insurance_contract c JOIN fgc.insurer i ON i.insurer_id = c.insurer_id
            WHERE (c.agent_id = ? OR EXISTS (SELECT 1 FROM fgc.contract_manager_assignment a
                    WHERE a.contract_id = c.contract_id AND a.agent_id = ? AND a.assignment_type = 'MAINTENANCE_MANAGER'
                      AND a.effective_from <= CURRENT_DATE AND (a.effective_to IS NULL OR a.effective_to >= CURRENT_DATE)))
            """;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired FgcUserDetailsService users;
    @Autowired AuthTokenService tokens;

    private Long ownAgent;
    private Long ownContract;
    private Long otherContract;
    private String agentToken;
    private final LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void prepare() {
        ownAgent = id("SELECT agent_id FROM fgc.agent WHERE agent_code = 'A-FC-001'");
        ownContract = id("SELECT min(contract_id) FROM fgc.insurance_contract WHERE agent_id = ?", ownAgent);
        otherContract = id("SELECT min(contract_id) FROM fgc.insurance_contract c WHERE c.agent_id <> ? AND NOT EXISTS ("
                + "SELECT 1 FROM fgc.contract_manager_assignment a WHERE a.contract_id = c.contract_id AND a.agent_id = ?)",
                ownAgent, ownAgent);
        jdbc.update("UPDATE fgc.app_user SET password_hash = ?, account_status = 'ACTIVE' WHERE login_id = 'agent01'",
                passwords.encode(UUID.randomUUID().toString()));
        agentToken = tokens.login((FgcUserDetails) users.loadUserByUsername("agent01")).accessToken();
    }

    @Test
    void portfolioBreakdownsShareOnePopulationAndMatchManualCounts() throws Exception {
        // 기준일 이후 계약일은 모집단에서 빠진다(기간은 contractDate 기준).
        long all = id("SELECT count(*) " + SCOPED + " AND c.contract_date <= ?", ownAgent, ownAgent, today);
        assertThat(all).isPositive();

        JsonNode data = data(home("period=ALL&asOf=" + today));
        JsonNode portfolio = data.path("portfolio");
        assertThat(portfolio.path("contractCount").asLong()).isEqualTo(all);
        assertThat(sum(portfolio.path("insurers"), "contractCount")).isEqualTo(all);
        assertThat(sum(portfolio.path("monthly"), "newContractCount")).isEqualTo(all);
        assertThat(sum(portfolio.path("categories"), "count")).isEqualTo(all);
        assertThat(sum(portfolio.path("statuses"), "count")).isEqualTo(all);
        BigDecimal premium = jdbc.queryForObject("SELECT COALESCE(sum(c.monthly_equivalent_first_premium), 0) " + SCOPED
                + " AND c.contract_date <= ?", BigDecimal.class, ownAgent, ownAgent, today);
        BigDecimal premiumSum = BigDecimal.ZERO;
        for (JsonNode m : portfolio.path("monthly")) {
            premiumSum = premiumSum.add(m.path("monthlyEquivalentFirstPremiumSum").decimalValue());
        }
        assertThat(premiumSum).isEqualByComparingTo(premium);

        long life = data(home("period=ALL&insurerType=LIFE&asOf=" + today)).path("portfolio").path("contractCount").asLong();
        long nonLife = data(home("period=ALL&insurerType=NON_LIFE&asOf=" + today)).path("portfolio").path("contractCount").asLong();
        assertThat(life + nonLife).isEqualTo(all);
    }

    @Test
    void periodBoundariesIncludeTheFirstDayOfTheWindowAndExcludeEarlierContracts() throws Exception {
        LocalDate from = today.withDayOfMonth(1).minusMonths(5);
        long expected = id("SELECT count(*) " + SCOPED + " AND c.contract_date BETWEEN ? AND ?",
                ownAgent, ownAgent, from, today);
        JsonNode data = data(home("period=6&asOf=" + today));
        assertThat(data.path("range").path("from").asText()).isEqualTo(from.toString());
        assertThat(data.path("range").path("to").asText()).isEqualTo(today.toString());
        assertThat(data.path("portfolio").path("contractCount").asLong()).isEqualTo(expected);
        assertThat(data.path("portfolio").path("monthly")).hasSize(6);
    }

    @Test
    void emptyPopulationIsZeroNotAnError() throws Exception {
        JsonNode data = data(home("period=6&asOf=2000-01-31"));
        assertThat(data.path("portfolio").path("contractCount").asLong()).isZero();
        assertThat(data.path("portfolio").path("insurers")).isEmpty();
    }

    @Test
    void pastAsOfWithoutStatusHistoryIsPartialAndNotReplacedByTheCurrentStatus() throws Exception {
        LocalDate asOf = today.minusYears(1);
        jdbc.update("UPDATE fgc.insurance_contract SET contract_date = ? WHERE contract_id = ?", asOf.minusDays(1), ownContract);
        jdbc.update("ALTER TABLE fgc.contract_status_event DISABLE TRIGGER trg_contract_status_event_append_only");
        jdbc.update("DELETE FROM fgc.contract_status_event WHERE contract_id = ?", ownContract);
        JsonNode data = data(home("period=ALL&asOf=" + asOf));
        assertThat(data.path("dataStatus").asText()).isEqualTo("PARTIAL");
        assertThat(data.path("basis").path("warnings")).isNotEmpty();
        boolean unknown = false;
        for (JsonNode s : data.path("portfolio").path("statuses")) {
            unknown |= "UNKNOWN".equals(s.path("code").asText());
        }
        assertThat(unknown).isTrue();
    }

    @Test
    void myCommissionKeepsExpectedAndHeldSeparateForTheAsOfMonth() throws Exception {
        LocalDate month = today.withDayOfMonth(1);
        long headerId = scheduleHeader();
        scheduleLine(headerId, 1, 1, "PLANNED", month.plusDays(2), 100000);
        scheduleLine(headerId, 2, 2, "HOLD", month.plusDays(3), 70000);
        JsonNode commission = data(home("asOf=" + today)).path("myCommission");
        assertThat(commission.path("month").asText()).isEqualTo(month.toString().substring(0, 7));
        assertThat(commission.path("expected").path("count").asLong()).isEqualTo(1);
        assertThat(commission.path("expected").path("amount").decimalValue()).isEqualByComparingTo("100000");
        assertThat(commission.path("held").path("count").asLong()).isEqualTo(1);
        assertThat(commission.path("held").path("amount").decimalValue()).isEqualByComparingTo("70000");
        JsonNode priority = data(home("asOf=" + today)).path("priorityContracts");
        boolean heldCandidate = false;
        for (JsonNode p : priority) {
            heldCandidate |= p.path("contractId").asLong() == ownContract
                    && p.path("reasonCodes").toString().contains("HOLD_UNRESOLVED");
        }
        assertThat(heldCandidate).isTrue();
        JsonNode item = priorityItem(ownContract, today);
        assertThat(item.path("heldCount").asLong()).isEqualTo(1);
        assertThat(item.path("heldAmount").decimalValue()).isEqualByComparingTo("70000");
        assertThat(item.path("expectedAmount").decimalValue()).isEqualByComparingTo("100000");
        assertThat(item.path("currentStatusCode").asText()).isNotBlank();
    }

    @Test
    void anEventRegisteredAfterTheCheckWithAnEarlierReceivedAtStillComesBack() throws Exception {
        addEvent("UNPAID");
        record(ownContract, "CLOSED", null, null, version(ownContract), "k-retro-1").andExpect(status().isOk());
        assertThat(reasons(ownContract, today)).isNull();
        // 보험사에서 어제 받은 사건을 확인 뒤에 소급 등록: 접수일시는 확인 시각보다 과거여도 순번이 더 크다.
        jdbc.update("""
                INSERT INTO fgc.contract_status_event(contract_id, event_seq, new_status, effective_at, received_at,
                    reason_code, source_system, source_event_key)
                SELECT ?, max(event_seq) + 1, 'LAPSED', clock_timestamp() - interval '2 days',
                       clock_timestamp() - interval '1 day', 'TEST', 'IT-HOME', ?
                  FROM fgc.contract_status_event WHERE contract_id = ?
                """, ownContract, UUID.randomUUID().toString(), ownContract);
        assertThat(reasons(ownContract, today)).contains("STATUS_EVENT_UNCONFIRMED");
    }

    @Test
    void aPlannedLineTurnedHoldAfterTheCheckIsStillAnUnresolvedHold() throws Exception {
        long header = scheduleHeader();
        scheduleLine(header, 1, 1, "PLANNED", today.withDayOfMonth(1).plusDays(2), 90000);
        record(ownContract, "CLOSED", null, null, version(ownContract), "k-hold-1").andExpect(status().isOk());
        assertThat(reasons(ownContract, today)).isNull();
        // 사건으로 기존 회차가 HOLD 로 바뀐다(생성 시각은 그대로). 사건 자체는 확인 대상 상태가 아니다.
        addEvent("ACTIVE");
        jdbc.update("UPDATE fgc.schedule_line SET line_status = 'HOLD' WHERE schedule_header_id = ?", header);
        assertThat(reasons(ownContract, today)).contains("HOLD_UNRESOLVED").doesNotContain("STATUS_EVENT_UNCONFIRMED");
        assertThat(priorityItem(ownContract, today).path("heldAmount").decimalValue()).isEqualByComparingTo("90000");
    }

    @Test
    void closedCheckHidesTheSameEventAndANewEventBringsTheContractBack() throws Exception {
        addEvent("UNPAID");
        assertThat(reasons(ownContract, today)).contains("STATUS_EVENT_UNCONFIRMED");

        record(ownContract, "CLOSED", null, "확인 완료", version(ownContract), "k-close-1").andExpect(status().isOk());
        assertThat(reasons(ownContract, today)).isNull();

        addEvent("LAPSED");
        assertThat(reasons(ownContract, today)).contains("STATUS_EVENT_UNCONFIRMED");
    }

    @Test
    void followUpComesBackOnTheNextCheckDateAndNotBefore() throws Exception {
        LocalDate next = today.plusDays(5);
        record(ownContract, "FOLLOW_UP", next, "재확인", version(ownContract), "k-follow-1").andExpect(status().isOk());
        assertThat(reasons(ownContract, today)).isNull();
        assertThat(reasons(ownContract, next)).contains("FOLLOW_UP");
        JsonNode item = priorityItem(ownContract, next);
        assertThat(item.path("nextCheckDate").asText()).isEqualTo(next.toString());
        assertThat(item.path("lastCheck").path("result").asText()).isEqualTo("FOLLOW_UP");
    }

    @Test
    void sameKeyAndBodyReplaysTheFirstResultAndADifferentBodyConflicts() throws Exception {
        String version = version(ownContract);
        JsonNode first = body(record(ownContract, "CLOSED", null, "메모", version, "k-idem-1").andExpect(status().isOk()));
        JsonNode replay = body(record(ownContract, "CLOSED", null, "메모", version, "k-idem-1").andExpect(status().isOk()));
        assertThat(replay.path("data")).isEqualTo(first.path("data"));
        assertThat(id("SELECT count(*) FROM fgc.agent_contract_check WHERE agent_id = ? AND contract_id = ?",
                ownAgent, ownContract)).isEqualTo(1L);
        record(ownContract, "CLOSED", null, "다른 메모", version, "k-idem-1")
                .andExpect(status().isConflict()).andExpect(content().string(containsString("IDEMPOTENCY_CONFLICT")));
    }

    @Test
    void staleContextOtherAgentsContractAndInvalidRequestsAreRejected() throws Exception {
        record(ownContract, "CLOSED", null, null, "contract" + ownContract + "-event999", "k-stale-1")
                .andExpect(status().isConflict()).andExpect(content().string(containsString("STATE_CHANGED")));
        record(otherContract, "CLOSED", null, null, version(otherContract), "k-other-1").andExpect(status().isForbidden());
        // FUN-085·086: 본인 기록에 사용한 키를 남의 계약에 재사용해도 멱등 충돌(409)이 아니라 범위 거절(403)이다.
        record(ownContract, "CLOSED", null, null, version(ownContract), "k-owned-1").andExpect(status().isOk());
        record(otherContract, "CLOSED", null, null, version(otherContract), "k-owned-1")
                .andExpect(status().isForbidden());
        assertThat(id("SELECT count(*) FROM fgc.agent_contract_check WHERE agent_id = ? AND idempotency_key = ?",
                ownAgent, "k-owned-1")).isEqualTo(1L);
        record(ownContract, "CLOSED", today.plusDays(1), null, version(ownContract), "k-bad-1").andExpect(status().isBadRequest());
        record(ownContract, "FOLLOW_UP", null, null, version(ownContract), "k-bad-2").andExpect(status().isBadRequest());
        record(ownContract, "CLOSED", null, "x".repeat(1001), version(ownContract), "k-bad-3").andExpect(status().isBadRequest());
        record(ownContract, "CLOSED", null, null, version(ownContract), null).andExpect(status().isBadRequest());
        // 위에서 성공한 본인 계약 확인 1건 외에 거절된 요청은 어떤 행도 추가하지 않는다.
        assertThat(id("SELECT count(*) FROM fgc.agent_contract_check")).isEqualTo(1L);
    }

    @Test
    void otherAgentsContractsNeverAppearInThePriorityList() throws Exception {
        addEventTo(otherContract, "TERMINATED");
        for (JsonNode p : data(home("asOf=" + today)).path("priorityContracts")) {
            assertThat(p.path("contractId").asLong()).isNotEqualTo(otherContract);
        }
    }

    private String reasons(Long contractId, LocalDate asOf) throws Exception {
        JsonNode item = priorityItem(contractId, asOf);
        return item == null ? null : item.path("reasonCodes").toString();
    }

    private JsonNode priorityItem(Long contractId, LocalDate asOf) throws Exception {
        for (JsonNode p : data(home("asOf=" + asOf)).path("priorityContracts")) {
            if (p.path("contractId").asLong() == contractId) {
                return p;
            }
        }
        return null;
    }

    private String version(Long contractId) {
        return "contract" + contractId + "-event"
                + id("SELECT COALESCE(max(event_seq), 0) FROM fgc.contract_status_event WHERE contract_id = ?", contractId);
    }

    private void addEvent(String newStatus) {
        addEventTo(ownContract, newStatus);
    }

    private void addEventTo(Long contractId, String newStatus) {
        jdbc.update("""
                INSERT INTO fgc.contract_status_event(contract_id, event_seq, previous_status, new_status, effective_at,
                    received_at, reason_code, source_system, source_event_key)
                SELECT ?, COALESCE(max(event_seq), 0) + 1, NULL, ?, clock_timestamp(), clock_timestamp(), 'TEST', 'IT-HOME', ?
                  FROM fgc.contract_status_event WHERE contract_id = ?
                """, contractId, newStatus, UUID.randomUUID().toString(), contractId);
    }

    private long scheduleHeader() {
        return id("""
                INSERT INTO fgc.schedule_header(contract_id, payment_stage, policy_version_id, schedule_version_no, schedule_regime)
                SELECT ?, 'GA_TO_FC', min(pv.policy_version_id), 1, 'CURRENT' FROM fgc.policy_version pv
                RETURNING schedule_header_id
                """, ownContract);
    }

    private void scheduleLine(long header, int lineNo, int installment, String lineStatus, LocalDate due, int amount) {
        Long item = id("SELECT min(commission_item_id) FROM fgc.commission_item");
        jdbc.update("""
                INSERT INTO fgc.schedule_line(schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, beneficiary_agent_id, basis_code, basis_amount, calculation_type, rate_pct,
                    expected_amount, line_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'FIRST_YEAR_PREMIUM', 1000000, 'RATE', 10, ?, ?)
                """, header, lineNo, installment, installment, due, item, ownAgent, amount, lineStatus);
    }

    private ResultActions home(String query) throws Exception {
        return mvc.perform(get("/api/v1/agent/me/home?" + query).header(HttpHeaders.AUTHORIZATION, "Bearer " + agentToken));
    }

    private ResultActions record(Long contractId, String result, LocalDate next, String memo, String version, String key)
            throws Exception {
        var body = json.createObjectNode().put("contractId", contractId).put("result", result)
                .put("expectedContextVersion", version);
        if (next != null) {
            body.put("nextCheckDate", next.toString());
        }
        if (memo != null) {
            body.put("memo", memo);
        }
        var request = post("/api/v1/agent/me/contract-checks").header(HttpHeaders.AUTHORIZATION, "Bearer " + agentToken)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private JsonNode data(ResultActions result) throws Exception {
        return body(result).path("data");
    }

    private long sum(JsonNode array, String field) {
        long total = 0;
        for (JsonNode n : array) {
            total += n.path(field).asLong();
        }
        return total;
    }

    private Long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
