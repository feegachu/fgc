package com.susukkang.fgc.policy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.policy.repository.PolicyVersionRepository;
import com.susukkang.fgc.policy.repository.FeeRegimeResolverRepository;
import com.susukkang.fgc.schedule.service.ScheduleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** FUN-017·제49조: 무기한 시드를 실제로 승계한다. 다른 회귀 테스트의 시드를 바꾸지 않는 별도 일회성 DB다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_policy_succession?currentSchema=fgc")
@AutoConfigureMockMvc
class PolicySuccessionIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PolicyVersionRepository policies;
    @Autowired private ScheduleService schedules;
    @Autowired private FeeRegimeResolverRepository feeRules;
    @MockitoBean(name = "policyClock") private Clock policyClock;

    @BeforeEach
    void fixBusinessClock() {
        when(policyClock.instant()).thenReturn(Instant.parse("2026-10-29T00:00:00Z"));
        when(policyClock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void appliesChangedRatesOnlyFromCutoverAndKeepsOlderContractsOnTheirOriginalVersion() throws Exception {
        FgcUserDetails writer = actor("settle01"), approver = actor("gaadmin");
        long originalId = jdbc.queryForObject("SELECT policy_version_id FROM fgc.policy_version WHERE policy_code='GA-CUR-2026-V1' AND status='ACTIVE'", Long.class);
        JsonNode original = detail(originalId, writer);
        var historicMoney = jdbc.queryForList("SELECT commission_transaction_id, policy_version_id, amount FROM fgc.commission_transaction WHERE policy_version_id=? ORDER BY commission_transaction_id", originalId);
        assertThat(historicMoney).isNotEmpty();
        LocalDate cutover = LocalDate.now(policyClock).plusMonths(1).withDayOfMonth(1);
        long sourceContractId = jdbc.queryForList("""
                SELECT c.contract_id FROM fgc.insurance_contract c
                JOIN fgc.product_offering p ON p.product_offering_id=c.product_offering_id
                WHERE p.sales_end_date IS NULL OR p.sales_end_date >= ?
                ORDER BY c.contract_id
                """, Long.class, cutover.plusDays(5)).stream()
                .filter(id -> policies.findApplicableCurrentCommissionPolicies(id, PaymentStage.GA_TO_FC).stream()
                        .anyMatch(policy -> policy.getPolicyVersionId() == originalId)).findFirst().orElseThrow();
        LocalDate beforeDate = jdbc.queryForObject("SELECT contract_date FROM fgc.insurance_contract WHERE contract_id=?", LocalDate.class, sourceContractId);
        long contractId = copyContract(sourceContractId, beforeDate);
        schedules.generateSchedulesByContractId(contractId);
        var originalAmounts = amounts(contractId);
        assertThat(originalAmounts).isNotEmpty();

        JsonNode sameStart = clone(originalId, original.path("header").path("effectiveFrom").asText(), writer);
        JsonNode error = action(sameStart.path("policyVersionId").asLong(), "SUBMIT", sameStart, writer, 400);
        assertThat(error.path("field").asText()).isEqualTo("effectiveFrom");

        String earlierDate = java.time.LocalDate.parse(original.path("header").path("effectiveFrom").asText()).minusDays(1).toString();
        JsonNode earlier = clone(originalId, earlierDate, writer);
        assertThat(action(earlier.path("policyVersionId").asLong(), "SUBMIT", earlier, writer, 400)
                .path("field").asText()).isEqualTo("effectiveFrom");

        JsonNode changed = clone(originalId, cutover.toString(), writer);
        long changedId = changed.path("policyVersionId").asLong();
        ObjectNode changedDocument = (ObjectNode) detail(changedId, writer).path("editDocument").deepCopy();
        for (JsonNode rule : changedDocument.path("commissionRules")) {
            ((ObjectNode) rule).put("calculationType", "RATE").put("ratePct", "12.000000").putNull("fixedAmount");
        }
        JsonNode edited = write("/api/v1/policies/" + changedId, changedDocument, writer, 200, true);
        long replacementId = changedId;
        JsonNode approved = action(replacementId, "APPROVE", action(replacementId, "SUBMIT", edited, writer, 200), approver, 200);
        assertThat(detail(originalId, writer).path("header").path("status").asText()).isEqualTo("ACTIVE");
        action(replacementId, "ACTIVATE", approved, approver, 200);
        schedules.regenerateContractSchedules(contractId, "요율 변경 후 기존 계약 재계산");
        assertThat(amounts(contractId)).isEqualTo(originalAmounts);
        assertThat(jdbc.queryForObject("SELECT policy_version_id FROM fgc.schedule_header WHERE contract_id=? AND payment_stage='GA_TO_FC' AND active_yn",
                Long.class, contractId)).isEqualTo(originalId);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fgc.schedule_line l JOIN fgc.schedule_header h USING(schedule_header_id)
                WHERE h.contract_id=? AND h.payment_stage='GA_TO_FC' AND h.active_yn AND l.due_date >= ?
                """, Integer.class, contractId, cutover)).isPositive();

        for (LocalDate date : java.util.List.of(cutover.minusDays(1), cutover, cutover.plusDays(1))) {
            long fresh = copyContract(sourceContractId, date);
            schedules.generateSchedulesByContractId(fresh);
            assertSelected(fresh, date.isBefore(cutover) ? originalId : replacementId);
            assertThat(amounts(fresh)).isNotEmpty();
            if (date.isBefore(cutover)) {
                assertThat(amounts(fresh)).isEqualTo(originalAmounts);
                continue;
            }
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fgc.schedule_header h JOIN fgc.schedule_line l USING(schedule_header_id)
                    WHERE h.contract_id=? AND h.payment_stage='GA_TO_FC' AND h.active_yn
                      AND (l.rate_pct <> 12 OR l.expected_amount <> round(l.basis_amount * 12 / 100, 0))
                    """, Integer.class, fresh)).isZero();
        }

        JsonNode retired = detail(originalId, writer);
        assertThat(retired.path("header").path("status").asText()).isEqualTo("RETIRED");
        assertThat(retired.path("editDocument").path("effectiveTo").isNull()).isTrue();
        assertThat(retired.path("applicationPeriod").path("to").asText()).isEqualTo(cutover.minusDays(1).toString());
        assertThat(retired.path("applicationPeriod").path("successorPolicyVersionId").asLong()).isEqualTo(replacementId);
        assertThat(retired.path("editDocument").path("commissionRules")).isEqualTo(original.path("editDocument").path("commissionRules"));
        assertThat(jdbc.queryForList("SELECT commission_transaction_id, policy_version_id, amount FROM fgc.commission_transaction WHERE policy_version_id=? ORDER BY commission_transaction_id", originalId)).isEqualTo(historicMoney);
        assertThat(policies.findApplicableCurrentCommissionPolicies(contractId, PaymentStage.GA_TO_FC))
                .extracting(policy -> policy.getPolicyVersionId()).containsExactly(originalId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.audit_log WHERE entity_type='POLICY_VERSION' AND entity_id=? AND action_code='POLICY_RETIRE'", Integer.class, Long.toString(originalId))).isEqualTo(1);

        // 아직 적용 전인 발행본은 같은 적용일로 정정한다. 폐기된 미래 판의 빈 구간은 선택하지 않는다.
        JsonNode correction = clone(replacementId, cutover.toString(), writer);
        long correctionId = correction.path("policyVersionId").asLong();
        ObjectNode correctedDocument = (ObjectNode) detail(correctionId, writer).path("editDocument").deepCopy();
        for (JsonNode rule : correctedDocument.path("commissionRules")) ((ObjectNode) rule).put("ratePct", "18.000000");
        JsonNode corrected = write("/api/v1/policies/" + correctionId, correctedDocument, writer, 200, true);
        action(correctionId, "ACTIVATE", action(correctionId, "APPROVE", action(correctionId, "SUBMIT", corrected, writer, 200), approver, 200), approver, 200);
        assertThat(detail(replacementId, writer).path("applicationPeriod").isNull()).isTrue();
        assertSelected(copyContract(sourceContractId, cutover), correctionId);
        assertSelected(contractId, originalId);
        LocalDate nextCutover = cutover.plusDays(2);
        JsonNode third = clone(correctionId, nextCutover.toString(), writer);
        long thirdId = third.path("policyVersionId").asLong();
        action(thirdId, "ACTIVATE", action(thirdId, "APPROVE", action(thirdId, "SUBMIT", third, writer, 200), approver, 200), approver, 200);
        assertSelected(copyContract(sourceContractId, cutover), correctionId);
        assertSelected(copyContract(sourceContractId, nextCutover), thirdId);
        schedules.regenerateContractSchedules(contractId, "세 번째 판 발행 후 기존 계약 재계산");
        assertSelected(contractId, originalId);
        assertThat(amounts(contractId)).isEqualTo(originalAmounts);
    }

    @Test
    void selectsHistoricalRegimeRulesAndTheirLimitsByContractDate() throws Exception {
        FgcUserDetails writer = actor("settle01"), approver = actor("gaadmin");
        long sourceId = jdbc.queryForObject("SELECT policy_version_id FROM fgc.policy_version WHERE policy_code='REG-FOUR-YEAR-2027-V1' AND status='ACTIVE'", Long.class);
        long currentId = jdbc.queryForObject("SELECT MIN(policy_version_id) FROM fgc.policy_version WHERE policy_code='GA-CUR-2026-V1'", Long.class);
        JsonNode draft = clone(sourceId, "2028-01-01", writer);
        long id = draft.path("policyVersionId").asLong();
        ObjectNode edit = (ObjectNode) detail(id, writer).path("editDocument").deepCopy();
        ObjectNode rule = (ObjectNode) detail(currentId, writer).path("editDocument").path("commissionRules").get(0).deepCopy();
        rule.remove("commissionRuleId");
        rule.put("feeComponentType", "UPFRONT").put("installmentFrom", 1).put("installmentTo", 1);
        edit.withArray("commissionRules").add(rule);
        JsonNode updated = write("/api/v1/policies/" + id, edit, writer, 200, true);
        action(id, "ACTIVATE", action(id, "APPROVE", action(id, "SUBMIT", updated, writer, 200), approver, 200), approver, 200);
        for (LocalDate contractDate : java.util.List.of(LocalDate.of(2027, 12, 31), LocalDate.of(2028, 1, 1))) {
            var candidates = feeRules.findRulesetCandidates("FOUR_YEAR_COMMISSION", "FOUR_YEAR_2027", contractDate,
                    LocalDate.of(2027, 1, 1), "BD-2027-01", LocalDate.of(2027, 1, 1));
            assertThat(candidates).hasSize(1);
            assertThat(candidates.getFirst().getPolicyVersionId()).isEqualTo(contractDate.getYear() == 2027 ? sourceId : id);
            assertThat(feeRules.findRulesetLimits(candidates.getFirst().getRulesetId())).hasSize(3);
        }
    }

    @Test
    void keepsArbitrageParametersOnTheVersionSelectedByValidationDate() throws Exception {
        FgcUserDetails writer = actor("settle01"), approver = actor("gaadmin");
        long sourceId = jdbc.queryForObject("SELECT policy_version_id FROM fgc.policy_version WHERE policy_type='ARBITRAGE' AND status='ACTIVE'", Long.class);
        LocalDate cutover = LocalDate.now(policyClock).plusMonths(1).withDayOfMonth(1);
        String originalMonths = policies.findArbitrageApplicability(cutover.minusDays(1)).getFirst().getRefundAdditionMonths();
        JsonNode copy = clone(sourceId, cutover.toString(), writer);
        long id = copy.path("policyVersionId").asLong();
        ObjectNode edit = (ObjectNode) detail(id, writer).path("editDocument").deepCopy();
        for (JsonNode parameter : edit.path("parameters")) {
            if (parameter.path("key").asText().equals("ARBITRAGE_REFUND_ADDITION_MONTHS")) ((ObjectNode) parameter).put("value", 24);
        }
        JsonNode updated = write("/api/v1/policies/" + id, edit, writer, 200, true);
        action(id, "ACTIVATE", action(id, "APPROVE", action(id, "SUBMIT", updated, writer, 200), approver, 200), approver, 200);
        var before = policies.findArbitrageApplicability(cutover.minusDays(1));
        var after = policies.findArbitrageApplicability(cutover);
        assertThat(before).hasSize(1);
        assertThat(before.getFirst().getPolicyVersionId()).isEqualTo(sourceId);
        assertThat(before.getFirst().getRefundAdditionMonths()).isEqualTo(originalMonths);
        assertThat(after).hasSize(1);
        assertThat(after.getFirst().getPolicyVersionId()).isEqualTo(id);
        assertThat(after.getFirst().getRefundAdditionMonths()).isEqualTo("24");
    }

    private void assertSelected(long contractId, long policyId) {
        assertThat(policies.findApplicableCurrentCommissionPolicies(contractId, PaymentStage.GA_TO_FC))
                .extracting(policy -> policy.getPolicyVersionId()).containsExactly(policyId);
    }

    private long copyContract(long sourceId, LocalDate contractDate) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.insurance_contract
                (insurer_id,product_offering_id,contract_no,contract_date,agent_id,organization_id,
                 premium_per_cycle_amount,first_premium_amount,monthly_equivalent_first_premium,payment_term_months)
                SELECT insurer_id,product_offering_id,?,?,agent_id,organization_id,
                 premium_per_cycle_amount,first_premium_amount,monthly_equivalent_first_premium,payment_term_months
                FROM fgc.insurance_contract WHERE contract_id=? RETURNING contract_id
                """, Long.class, "P647-" + UUID.randomUUID(), contractDate, sourceId);
    }

    private java.util.List<java.util.Map<String, Object>> amounts(long contractId) {
        return jdbc.queryForList("""
                SELECT l.commission_item_id,l.installment_no,l.expected_amount FROM fgc.schedule_header h
                JOIN fgc.schedule_line l ON l.schedule_header_id=h.schedule_header_id
                WHERE h.contract_id=? AND h.payment_stage='GA_TO_FC' AND h.active_yn
                ORDER BY l.commission_item_id,l.installment_no
                """, contractId);
    }

    private FgcUserDetails actor(String login) {
        return jdbc.queryForObject("""
                SELECT u.user_id,u.login_id,u.password_hash,u.user_name,r.role_code,u.account_status
                FROM fgc.app_user u JOIN fgc.app_role r ON r.role_id=u.role_id WHERE u.login_id=?
                """, (rs, row) -> new FgcUserDetails(new AppUserView(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6)), true, true), login);
    }

    private JsonNode detail(long id, FgcUserDetails actor) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/policies/" + id).with(user(actor))).andReturn()
                .getResponse().getContentAsByteArray()).path("data");
    }

    private JsonNode clone(long id, String from, FgcUserDetails actor) throws Exception {
        return write("/api/v1/policies/" + id + "/versions", mapper.createObjectNode()
                .put("effectiveFrom", from).put("reason", "전체 대체본 승계"), actor, 201);
    }

    private JsonNode action(long id, String action, JsonNode version, FgcUserDetails actor, int status) throws Exception {
        return write("/api/v1/policies/" + id + "/actions", mapper.createObjectNode().put("actionType", action)
                .put("reason", "승계 검증").put("expectedStatus", version.path("status").asText())
                .put("expectedUpdatedAt", version.path("updatedAt").asText()), actor, status);
    }

    private JsonNode write(String path, ObjectNode body, FgcUserDetails actor, int status) throws Exception {
        return write(path, body, actor, status, false);
    }

    private JsonNode write(String path, ObjectNode body, FgcUserDetails actor, int status, boolean update) throws Exception {
        var request = update ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path) : post(path);
        var response = mvc.perform(request.with(user(actor)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(APPLICATION_JSON).content(mapper.writeValueAsBytes(body))).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(status);
        return mapper.readTree(response.getContentAsByteArray()).path(status < 400 ? "data" : "error");
    }
}
