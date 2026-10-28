package com.susukkang.fgc.policy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.policy.repository.PolicyVersionRepository;
import com.susukkang.fgc.schedule.service.ScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

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

    @Test
    void replacesOpenEndedSeedWithoutLosingPastContractEligibilityOrStoredMoneyReferences() throws Exception {
        FgcUserDetails writer = actor("settle01"), approver = actor("gaadmin");
        long originalId = jdbc.queryForObject("SELECT policy_version_id FROM fgc.policy_version WHERE policy_code='GA-CUR-2026-V1' AND status='ACTIVE'", Long.class);
        JsonNode original = detail(originalId, writer);
        var historicMoney = jdbc.queryForList("SELECT commission_transaction_id, policy_version_id, amount FROM fgc.commission_transaction WHERE policy_version_id=? ORDER BY commission_transaction_id", originalId);
        assertThat(historicMoney).isNotEmpty();
        long contractId = jdbc.queryForList("SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id", Long.class).stream()
                .filter(id -> policies.findApplicableCurrentCommissionPolicies(id, PaymentStage.GA_TO_FC).stream()
                        .anyMatch(policy -> policy.getPolicyVersionId() == originalId)).findFirst().orElseThrow();
        contractId = copyContract(contractId);
        schedules.generateSchedulesByContractId(contractId);
        var originalAmounts = amounts(contractId);
        assertThat(originalAmounts).isNotEmpty();

        JsonNode narrow = clone(originalId, "2027-01-01", writer);
        long narrowId = narrow.path("policyVersionId").asLong();
        JsonNode error = action(narrowId, "SUBMIT", narrow, writer, 400);
        assertThat(error.path("field").asText()).isEqualTo("effectiveFrom");

        String earlierDate = java.time.LocalDate.parse(original.path("header").path("effectiveFrom").asText()).minusDays(1).toString();
        JsonNode earlier = clone(originalId, earlierDate, writer);
        assertThat(action(earlier.path("policyVersionId").asLong(), "SUBMIT", earlier, writer, 400)
                .path("field").asText()).isEqualTo("effectiveFrom");

        JsonNode changed = clone(originalId, original.path("header").path("effectiveFrom").asText(), writer);
        long changedId = changed.path("policyVersionId").asLong();
        ObjectNode changedDocument = (ObjectNode) detail(changedId, writer).path("editDocument").deepCopy();
        ((ObjectNode) changedDocument.path("commissionRules").get(0)).put("ratePct", "23.456789");
        JsonNode edited = write("/api/v1/policies/" + changedId, changedDocument, writer, 200, true);
        assertThat(action(changedId, "SUBMIT", edited, writer, 400).path("field").asText()).isEqualTo("commissionRules");
        schedules.regenerateContractSchedules(contractId, "요율 변경 거절 후 재계산");
        assertThat(amounts(contractId)).isEqualTo(originalAmounts);

        JsonNode replacement = clone(originalId, original.path("header").path("effectiveFrom").asText(), writer);
        long replacementId = replacement.path("policyVersionId").asLong();
        JsonNode approved = action(replacementId, "APPROVE", action(replacementId, "SUBMIT", replacement, writer, 200), approver, 200);
        assertThat(detail(originalId, writer).path("header").path("status").asText()).isEqualTo("ACTIVE");
        action(replacementId, "ACTIVATE", approved, approver, 200);
        schedules.regenerateContractSchedules(contractId, "전체 대체본 승계 후 재계산");
        assertThat(amounts(contractId)).isEqualTo(originalAmounts);
        assertThat(jdbc.queryForObject("SELECT policy_version_id FROM fgc.schedule_header WHERE contract_id=? AND payment_stage='GA_TO_FC' AND active_yn",
                Long.class, contractId)).isEqualTo(replacementId);

        JsonNode retired = detail(originalId, writer);
        assertThat(retired.path("header").path("status").asText()).isEqualTo("RETIRED");
        assertThat(retired.path("editDocument").path("effectiveTo").isNull()).isTrue();
        assertThat(retired.path("editDocument").path("commissionRules")).isEqualTo(original.path("editDocument").path("commissionRules"));
        assertThat(jdbc.queryForList("SELECT commission_transaction_id, policy_version_id, amount FROM fgc.commission_transaction WHERE policy_version_id=? ORDER BY commission_transaction_id", originalId)).isEqualTo(historicMoney);
        assertThat(policies.findApplicableCurrentCommissionPolicies(contractId, PaymentStage.GA_TO_FC))
                .extracting(policy -> policy.getPolicyVersionId()).containsExactly(replacementId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.audit_log WHERE entity_type='POLICY_VERSION' AND entity_id=? AND action_code='POLICY_RETIRE'", Integer.class, Long.toString(originalId))).isEqualTo(1);
    }

    private long copyContract(long sourceId) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.insurance_contract
                (insurer_id,product_offering_id,contract_no,contract_date,agent_id,organization_id,
                 premium_per_cycle_amount,first_premium_amount,monthly_equivalent_first_premium,payment_term_months)
                SELECT insurer_id,product_offering_id,?,contract_date,agent_id,organization_id,
                 premium_per_cycle_amount,first_premium_amount,monthly_equivalent_first_premium,payment_term_months
                FROM fgc.insurance_contract WHERE contract_id=? RETURNING contract_id
                """, Long.class, "P647-" + UUID.randomUUID(), sourceId);
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
