package com.susukkang.fgc.report;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FUN-060: 실제 PostgreSQL·JWT 로 본인 범위, 4유형 분리, 목록·합계·상세 일치를 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AgentStatementIntegrationTest {
    private static final String MONTH = "2026-07";
    private static final String MONTH_FIRST = "2026-07-01";
    private static final String OWN_PAYMENTS = """
            FROM fgc.commission_transaction ct
            JOIN fgc.transaction_attribution ta USING (commission_transaction_id)
            WHERE ct.recipient_agent_id = ? AND ct.payment_stage = 'GA_TO_FC' AND ct.status = 'CONFIRMED'
              AND ct.cashflow_type = 'PAYMENT' AND ct.source_type NOT IN ('ADJUSTMENT', 'CLAWBACK', 'RECOVERY')
              AND ta.attribution_month = DATE '2026-07-01'
            """;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired FgcUserDetailsService users;
    @Autowired AuthTokenService tokens;

    private Long ownAgent;
    private Long otherAgent;
    private String agentToken;
    private String internalToken;

    @BeforeEach
    void prepare() {
        ownAgent = id("SELECT agent_id FROM fgc.agent WHERE agent_code = 'A-FC-001'");
        otherAgent = id("SELECT min(agent_id) FROM fgc.agent WHERE agent_id <> ?", ownAgent);
        jdbc.update("UPDATE fgc.app_user SET password_hash = ?, account_status = 'ACTIVE' WHERE login_id = 'agent01'",
                passwords.encode(UUID.randomUUID().toString()));
        agentToken = tokens.login((FgcUserDetails) users.loadUserByUsername("agent01")).accessToken();
        internalToken = tokens.login((FgcUserDetails) users.loadUserByUsername("settle01")).accessToken();
    }

    @Test
    void paymentListSummaryAndPagesAgreeWithTheLedger() throws Exception {
        long expectedCount = jdbc.queryForObject("SELECT count(*) " + OWN_PAYMENTS, Long.class, ownAgent);
        BigDecimal expectedAmount = jdbc.queryForObject("SELECT COALESCE(sum(ta.attributed_amount), 0) " + OWN_PAYMENTS,
                BigDecimal.class, ownAgent);
        assertThat(expectedCount).isGreaterThan(1);

        JsonNode first = data(mine("month=" + MONTH + "&size=2&page=1"));
        JsonNode second = data(mine("month=" + MONTH + "&size=2&page=2"));
        assertThat(first.path("summary").path("payment").path("count").asLong()).isEqualTo(expectedCount);
        assertThat(first.path("summary").path("payment").path("amount").decimalValue()).isEqualByComparingTo(expectedAmount);
        // 페이지를 바꿔도 전체 합계는 같고, totalElements 는 같은 모집단이다.
        assertThat(second.path("summary")).isEqualTo(first.path("summary"));
        assertThat(first.path("items").path("totalElements").asLong()).isEqualTo(expectedCount);
        assertThat(first.path("items").path("content")).hasSize(2);
        assertThat(first.path("basis").path("month").asText()).isEqualTo(MONTH);
        assertThat(first.path("summary").path("clawback").path("count").asLong()).isZero();

        BigDecimal allPages = BigDecimal.ZERO;
        for (int page = 1; page <= first.path("items").path("totalPages").asInt(); page++) {
            for (JsonNode item : data(mine("month=" + MONTH + "&size=2&page=" + page)).path("items").path("content")) {
                assertThat(item.path("entryType").asText()).isEqualTo("PAYMENT");
                allPages = allPages.add(item.path("amount").decimalValue());
            }
        }
        assertThat(allPages).isEqualByComparingTo(expectedAmount);
    }

    @Test
    void detailMatchesTheListEntryAndCarriesSourceEvidence() throws Exception {
        JsonNode entry = data(mine("month=" + MONTH + "&size=1")).path("items").path("content").get(0);
        JsonNode detail = data(mine("/" + entry.path("entryKey").asText()));
        assertThat(detail.path("entry")).isEqualTo(entry);
        assertThat(detail.path("evidence").path("commissionTransactionId").asLong()).isPositive();
        assertThat(entry.path("source").path("entityType").asText()).isEqualTo("TRANSACTION_ATTRIBUTION");
    }

    @Test
    void heldAdjustmentAndClawbackAreSeparatedWithoutDoubleCounting() throws Exception {
        Long item = id("SELECT min(commission_item_id) FROM fgc.commission_transaction WHERE recipient_agent_id = ?", ownAgent);
        // 시드 DB에는 운영 스케줄이 없으므로 본인 계약에 보류 회차 한 줄을 직접 만든다.
        Long header = id("""
                INSERT INTO fgc.schedule_header(contract_id, payment_stage, policy_version_id, schedule_version_no, schedule_regime)
                SELECT c.contract_id, 'GA_TO_FC', min(pv.policy_version_id), 1, 'CURRENT'
                FROM fgc.insurance_contract c CROSS JOIN fgc.policy_version pv
                WHERE c.contract_id = (SELECT min(contract_id) FROM fgc.insurance_contract WHERE agent_id = ?)
                GROUP BY c.contract_id RETURNING schedule_header_id
                """, ownAgent);
        Long line = id("""
                INSERT INTO fgc.schedule_line(schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, beneficiary_agent_id, basis_code, basis_amount, calculation_type, rate_pct,
                    expected_amount, line_status)
                VALUES (?, 1, 3, 3, DATE '2026-07-20', ?, ?, 'FIRST_YEAR_PREMIUM', 1000000, 'RATE', 10, 100000, 'HOLD')
                RETURNING schedule_line_id
                """, header, item, ownAgent);
        BigDecimal heldAmount = jdbc.queryForObject("SELECT expected_amount FROM fgc.schedule_line WHERE schedule_line_id = ?",
                BigDecimal.class, line);
        String heldMonth = jdbc.queryForObject("SELECT to_char(due_month, 'YYYY-MM') FROM fgc.schedule_line WHERE schedule_line_id = ?",
                String.class, line);

        // 정정 지급건은 DRAFT 로 넣고 귀속을 붙인 뒤 UPDATE 로 확정한다(원장 가드).
        Long adjustmentLine = id("""
                INSERT INTO fgc.schedule_line(schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, beneficiary_agent_id, basis_code, basis_amount, calculation_type, rate_pct,
                    expected_amount, line_status)
                VALUES (?, 2, 4, 4, DATE '2026-07-25', ?, ?, 'FIRST_YEAR_PREMIUM', 1000000, 'RATE', 1, 10000, 'PLANNED')
                RETURNING schedule_line_id
                """, header, item, ownAgent);
        Long adjustmentTransaction = id("""
                INSERT INTO fgc.commission_transaction(payment_stage, source_type, source_business_key, recipient_agent_id,
                    commission_item_id, settlement_month, due_date, amount, cashflow_type, status)
                VALUES ('GA_TO_FC', 'ADJUSTMENT', ?, ?, ?, DATE '2026-07-01', DATE '2026-07-25', 5000, 'DEDUCTION', 'DRAFT')
                RETURNING commission_transaction_id
                """, "STMT-ADJ-" + UUID.randomUUID(), ownAgent, item);
        jdbc.update("""
                INSERT INTO fgc.transaction_attribution(commission_transaction_id, attribution_seq, attribution_scope,
                    contract_id, agent_id, schedule_line_id, attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, exclusion_type_snapshot, attribution_method, allocation_basis_snapshot, evidence_ref)
                SELECT ?, 1, 'CONTRACT', sh.contract_id, ?, ?, DATE '2026-07-25', DATE '2026-07-01', 5000,
                       'EXCLUDED', 'NEW_AGENT_SUPPORT', 'DIRECT', '{}'::jsonb, 'EVD-STMT-ADJ'
                FROM fgc.schedule_header sh WHERE sh.schedule_header_id = ?
                """, adjustmentTransaction, ownAgent, adjustmentLine, header);
        jdbc.update("UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = ?", adjustmentTransaction);

        Long rule = id("""
                INSERT INTO fgc.clawback_rule(policy_version_id, elapsed_month_from, elapsed_month_to,
                    clawback_basis_code, clawback_rate_pct)
                SELECT min(policy_version_id), 9999, 9999, 'STMT_FIXTURE', 10 FROM fgc.policy_version
                RETURNING clawback_rule_id
                """);
        Long attribution = id("""
                SELECT min(a.transaction_attribution_id) FROM fgc.transaction_attribution a
                JOIN fgc.commission_transaction t USING (commission_transaction_id)
                WHERE t.recipient_agent_id = ? AND a.contract_id IS NOT NULL
                """, ownAgent);
        Long caseId = id("""
                INSERT INTO fgc.clawback_case(contract_id, policy_version_id, case_no, status, calculated_amount)
                SELECT a.contract_id, r.policy_version_id, ?, 'CALCULATED', 10 FROM fgc.transaction_attribution a
                CROSS JOIN fgc.clawback_rule r WHERE a.transaction_attribution_id = ? AND r.clawback_rule_id = ?
                RETURNING clawback_case_id
                """, "STMT-" + UUID.randomUUID(), attribution, rule);
        jdbc.update("""
                INSERT INTO fgc.clawback_line(clawback_case_id, line_no, transaction_attribution_id, clawback_rule_id,
                    originally_paid_amount, applied_rate_pct, clawback_amount) VALUES (?, 1, ?, ?, 100, 10, 10)
                """, caseId, attribution, rule);
        jdbc.update("UPDATE fgc.clawback_case SET created_at = TIMESTAMPTZ '2026-07-15 10:00:00+09' WHERE clawback_case_id = ?", caseId);

        JsonNode adjustment = data(mine("month=" + MONTH + "&entryType=ADJUSTMENT"));
        assertThat(adjustment.path("summary").path("adjustment").path("count").asLong()).isEqualTo(1);
        assertThat(adjustment.path("summary").path("adjustment").path("amount").decimalValue()).isEqualByComparingTo("-5000");
        assertThat(adjustment.path("summary").path("payment").path("count").asLong()).isZero();

        JsonNode clawback = data(mine("month=" + MONTH + "&entryType=CLAWBACK"));
        assertThat(clawback.path("summary").path("clawback").path("count").asLong()).isEqualTo(1);
        assertThat(clawback.path("summary").path("clawback").path("amount").decimalValue()).isEqualByComparingTo("10");
        JsonNode clawbackEntry = clawback.path("items").path("content").get(0);
        assertThat(clawbackEntry.path("statusLabel").asText()).isEqualTo("환수 계산 완료");
        assertThat(data(mine("/" + clawbackEntry.path("entryKey").asText())).path("evidence").path("appliedRatePct")
                .decimalValue()).isEqualByComparingTo("10");

        JsonNode held = data(mine("month=" + heldMonth + "&entryType=HELD"));
        assertThat(held.path("summary").path("held").path("amount").decimalValue()).isGreaterThanOrEqualTo(heldAmount);
        assertThat(held.path("items").path("content").findValuesAsText("entryType")).containsOnly("HELD");

        // 한 달에 네 유형이 함께 있어도 같은 지급건이 PAYMENT 와 ADJUSTMENT 에 같이 잡히지 않는다.
        JsonNode all = data(mine("month=" + MONTH + "&size=100"));
        long total = all.path("items").path("totalElements").asLong();
        JsonNode summary = all.path("summary");
        assertThat(total).isEqualTo(summary.path("payment").path("count").asLong() + summary.path("held").path("count").asLong()
                + summary.path("adjustment").path("count").asLong() + summary.path("clawback").path("count").asLong());
    }

    @Test
    void filtersAndSortApplyToListAndSummaryAlike() throws Exception {
        JsonNode all = data(mine("month=" + MONTH + "&size=100&sort=amount,desc"));
        JsonNode content = all.path("items").path("content");
        for (int i = 1; i < content.size(); i++) {
            assertThat(content.get(i - 1).path("amount").decimalValue())
                    .isGreaterThanOrEqualTo(content.get(i).path("amount").decimalValue());
        }
        String contractNo = content.get(0).path("contractNo").asText();
        JsonNode filtered = data(mine("month=" + MONTH + "&size=100&contractNo=" + contractNo.toLowerCase()));
        assertThat(filtered.path("items").path("content").findValuesAsText("contractNo")).containsOnly(contractNo);
        assertThat(filtered.path("summary").path("payment").path("count").asLong())
                .isEqualTo(filtered.path("items").path("totalElements").asLong());
        assertThat(data(mine("month=" + MONTH + "&entryType=HELD")).path("items").path("totalElements").asLong()).isZero();
    }

    @Test
    void anotherAgentsEntryAndInternalAccessAreBlocked() throws Exception {
        Long otherAttribution = id("""
                SELECT min(ta.transaction_attribution_id) FROM fgc.transaction_attribution ta
                JOIN fgc.commission_transaction ct USING (commission_transaction_id)
                WHERE ct.recipient_agent_id = ? AND ct.payment_stage = 'GA_TO_FC' AND ct.status = 'CONFIRMED'
                  AND ct.cashflow_type = 'PAYMENT'
                """, jdbc.queryForObject("""
                SELECT recipient_agent_id FROM fgc.commission_transaction
                WHERE recipient_agent_id <> ? AND payment_stage = 'GA_TO_FC' AND status = 'CONFIRMED' LIMIT 1
                """, Long.class, ownAgent));
        String foreignKey = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("PAYMENT:" + otherAttribution + ":0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // 남의 키는 없는 키와 같은 404 로 답한다.
        request("/api/v1/agent/me/statements/" + foreignKey, agentToken).andExpect(status().isNotFound());
        request("/api/v1/agent/me/statements/" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("PAYMENT:999999999:0".getBytes(java.nio.charset.StandardCharsets.UTF_8)), agentToken)
                .andExpect(status().isNotFound());
        request("/api/v1/agent/me/statements/not-a-key", agentToken).andExpect(status().isBadRequest());

        // AGENT 는 내부 경로에 agentId 를 넣어도 접근할 수 없다.
        request("/api/v1/reports/agents/" + ownAgent + "/statements?month=" + MONTH, agentToken).andExpect(status().isForbidden());
        request("/api/v1/reports/agents/" + otherAgent + "/statements?month=" + MONTH, agentToken).andExpect(status().isForbidden());
        // 내부 역할은 본인 경로를 쓸 수 없고, 내부 경로로는 지정한 설계사만 본다.
        request("/api/v1/agent/me/statements?month=" + MONTH, internalToken).andExpect(status().isForbidden());
        JsonNode internal = data(request("/api/v1/reports/agents/" + ownAgent + "/statements?month=" + MONTH, internalToken)
                .andExpect(status().isOk()));
        assertThat(internal.path("summary").path("payment").path("count").asLong()).isPositive();
        request("/api/v1/reports/agents/999999999/statements?month=" + MONTH, internalToken).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/agent/me/statements?month=" + MONTH)).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidInputsAreRejectedWithoutTouchingTheQuery() throws Exception {
        mine("").andExpect(status().isBadRequest());
        mine("month=2026-13").andExpect(status().isBadRequest());
        mine("month=" + MONTH + "&entryType=NET").andExpect(status().isBadRequest());
        mine("month=" + MONTH + "&sort=amount;DROP,asc").andExpect(status().isBadRequest());
        mine("month=" + MONTH + "&size=101").andExpect(status().isBadRequest());
        mine("month=" + MONTH + "&page=0").andExpect(status().isBadRequest());
        mine("month=" + MONTH + "&contractNo=" + "A".repeat(81)).andExpect(status().isBadRequest());
        mine("month=" + MONTH + "&contractNo=%25").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.totalElements").value(0));
    }

    private ResultActions mine(String queryOrPath) throws Exception {
        String path = queryOrPath.startsWith("/") ? "/api/v1/agent/me/statements" + queryOrPath
                : "/api/v1/agent/me/statements" + (queryOrPath.isEmpty() ? "" : "?" + queryOrPath);
        return request(path, agentToken);
    }

    private ResultActions request(String path, String token) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private Long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
