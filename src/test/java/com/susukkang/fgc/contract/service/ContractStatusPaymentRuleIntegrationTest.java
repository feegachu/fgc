package com.susukkang.fgc.contract.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.contract.dto.ContractStatusEffect;
import com.susukkang.fgc.contract.repository.ContractStatusEventRepository;
import com.susukkang.fgc.policy.service.ScheduleEligibilityPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.susukkang.fgc.contract.dto.ContractStatusEffect.EntityType.PAYMENT;
import static com.susukkang.fgc.contract.dto.ContractStatusEffect.EntityType.SCHEDULE_LINE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * FUN-015(#648) 인수 시험. ① 정책 API로 설정한 상태별 지급조건이 상세 조회와 판단까지 같은 policyVersionId로 이어진다
 * ② 잘못된 키·값·유형·누락·구간 중복을 거절하고 저장된 정책을 바꾸지 않는다(CT-P02) ③ 실제 DB의 확정·미확정 혼합
 * 계약에서 미확정만 판단하고 지급 보류와 회차 중단을 구분하며 실효 기간을 소급하지 않는다(CT-C02 판단 단계).
 * 시험 트랜잭션은 롤백되어 공유 DB에 정책·계약을 남기지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ContractStatusPaymentRuleIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ContractStatusPaymentRule rule;
    @Autowired private ContractStatusEventRepository events;
    @Autowired private ScheduleEligibilityPolicyService policies;

    private FgcUserDetails writer;
    private FgcUserDetails approver;

    @BeforeEach
    void createActors() {
        writer = insertUser("SETTLEMENT");
        approver = insertUser("GA_ADMIN");
    }

    @Test
    void policySavedThroughTheApiDrivesTheDecisionWithTheSamePolicyVersion() throws Exception {
        JsonNode created = create(scheduleDraft("2025-01-01", "2025-12-31"));
        long policyId = created.path("policyVersionId").asLong();
        JsonNode detail = detail(policyId);
        assertThat(detail.path("header").path("sourceClass").asText()).isEqualTo("PROJECT_ASSUMPTION");
        assertThat(detail.path("editDocument").path("parameters"))
                .extracting(p -> p.path("key").asText(), p -> p.path("value").asText(), p -> p.path("description").isTextual())
                .containsExactly(tuple("PRORATION_RULE", "NO_PRORATION", true),
                        tuple("REVIVAL_BACKFILL_RULE", "NO_RETROACTIVE", true),
                        tuple("STATUS_MONTH_RULE", "PAY_IF_ACTIVE_ON_DUE_DATE", true));
        JsonNode review = action(policyId, "SUBMIT", created, writer, 200);
        JsonNode approved = action(policyId, "APPROVE", review, approver, 200);
        assertThat(action(policyId, "ACTIVATE", approved, approver, 200).path("status").asText()).isEqualTo("ACTIVE");

        long contractId = contract("2025-06-01");
        statusEvent(contractId, 1, "ACTIVE", "2025-06-01T00:00:00+09:00");
        long lapse = statusEvent(contractId, 2, "LAPSED", "2025-09-01T00:00:00+09:00");
        long lineId = line(header(contractId), 1, "2025-09-25", "PLANNED");

        List<ContractStatusEffect> effects = rule.decide(events.findById(lapse).orElseThrow(), Set.of());

        assertThat(effects).extracting(ContractStatusEffect::entityId, ContractStatusEffect::nextState,
                        ContractStatusEffect::policyVersionId)
                .containsExactly(tuple(lineId, "CANCELLED", policyId));
    }

    /** V77 뒤 db/migration만 적용한 운영 DB의 복구 절차(POL-W01 새 버전 작성)가 기존 계약까지 덮는지 실제 API로 확인한다. */
    @Test
    void retiredLegacyVersionCanBeReissuedFromItsStartDateThroughTheApi() throws Exception {
        // 적용할 판이 없는 상태: 데모 v2를 승계 기록 없이 폐기한다(데모 V3·V77이 v1에 한 처리와 같다, 롤백됨).
        jdbc.update("UPDATE fgc.policy_version SET status = 'RETIRED' "
                + "WHERE policy_code = 'STATUS-MONTH-RULE-2026' AND status = 'ACTIVE'");
        Long legacy = jdbc.queryForObject("""
                SELECT policy_version_id FROM fgc.policy_version
                 WHERE policy_code = 'STATUS-MONTH-RULE-2026' AND version_no = 1
                """, Long.class);
        LocalDate existingContract = LocalDate.of(2026, 3, 1);
        assertThatThrownBy(() -> policies.resolve(existingContract)).isInstanceOf(FgcBusinessException.class);

        JsonNode cloned = write(HttpMethod.POST, "/api/v1/policies/" + legacy + "/versions",
                mapper.createObjectNode().put("effectiveFrom", "2026-01-01").put("reason", "유산 v1 재발행"), 201);
        long policyId = cloned.path("policyVersionId").asLong();
        ObjectNode edit = detail(policyId).path("editDocument").deepCopy();
        edit.set("parameters", scheduleDraft("2026-01-01", null).path("parameters"));
        edit.putArray("sourceRefs"); // 승인자 없는 v1의 코드 없는 문서 경로는 새 판에 남기지 않는다
        JsonNode updated = write(HttpMethod.PUT, "/api/v1/policies/" + policyId, edit, 200);
        JsonNode review = action(policyId, "SUBMIT", updated, writer, 200);
        JsonNode approved = action(policyId, "APPROVE", review, approver, 200);
        action(policyId, "ACTIVATE", approved, approver, 200);

        assertThat(policies.resolve(existingContract)).isEqualTo(policyId);
    }

    @Test
    void rejectsUnregisteredConditionsWithoutChangingTheSavedPolicy() throws Exception {
        JsonNode created = create(scheduleDraft("2024-01-01", "2024-12-31"));
        long policyId = created.path("policyVersionId").asLong();
        JsonNode saved = detail(policyId).path("editDocument");

        ObjectNode unknownKey = saved.deepCopy();
        ((ObjectNode) unknownKey.path("parameters").get(0)).put("key", "FREE_CONDITION");
        ObjectNode freeExpression = saved.deepCopy();
        ((ObjectNode) freeExpression.path("parameters").get(0))
                .set("value", mapper.createObjectNode().put("when", "status == 'ACTIVE'"));
        ObjectNode wrongType = saved.deepCopy();
        wrongType.put("policyType", "CURRENT_COMMISSION");
        for (ObjectNode invalid : List.of(unknownKey, freeExpression, wrongType)) {
            JsonNode error = writeError(HttpMethod.PUT, "/api/v1/policies/" + policyId, invalid, writer, 400);
            assertThat(error.path("code").asText()).isEqualTo("FGC-COMMON-002");
            assertThat(error.path("field").asText()).isEqualTo("parameters[0].value");
        }

        assertThat(detail(policyId).path("editDocument")).isEqualTo(saved);
    }

    @Test
    void submissionRequiresEveryConditionAndEvidenceAndApprovalRejectsOverlap() throws Exception {
        ObjectNode missingCondition = scheduleDraft("2023-01-01", "2023-12-31");
        ((ArrayNode) missingCondition.path("parameters")).remove(0);
        JsonNode incomplete = create(missingCondition);
        JsonNode missing = writeError(HttpMethod.POST, "/api/v1/policies/" + incomplete.path("policyVersionId").asLong()
                + "/actions", actionRequest("SUBMIT", incomplete), writer, 400);
        assertThat(missing.path("field").asText()).isEqualTo("policyType");

        ObjectNode noEvidence = scheduleDraft("2022-01-01", "2022-12-31");
        noEvidence.putArray("regulationRefs");
        JsonNode withoutEvidence = create(noEvidence);
        JsonNode evidence = writeError(HttpMethod.POST, "/api/v1/policies/" + withoutEvidence.path("policyVersionId").asLong()
                + "/actions", actionRequest("SUBMIT", withoutEvidence), writer, 400);
        assertThat(evidence.path("field").asText()).isEqualTo("sourceRefs");

        // 데모 STATUS-MONTH-RULE-2026(2026-01-01~)과 겹치는 다른 코드는 승인 단계에서 막는다.
        JsonNode overlapping = create(scheduleDraft("2026-03-01", null));
        long overlapId = overlapping.path("policyVersionId").asLong();
        JsonNode review = action(overlapId, "SUBMIT", overlapping, writer, 200);
        JsonNode overlap = writeError(HttpMethod.POST, "/api/v1/policies/" + overlapId + "/actions",
                actionRequest("APPROVE", review), approver, 422);
        assertThat(overlap.path("code").asText()).isEqualTo("FGC-POL-002");

        assertThat(jdbc.queryForList("""
                SELECT status FROM fgc.policy_version WHERE policy_version_id IN (?, ?, ?) ORDER BY policy_version_id
                """, String.class, incomplete.path("policyVersionId").asLong(),
                withoutEvidence.path("policyVersionId").asLong(), overlapId))
                .containsExactly("DRAFT", "DRAFT", "REVIEW");
    }

    @Test
    void mixedStateContractControlsOnlyUnconfirmedItemsAndNeverBackfillsTheLapse() {
        Long policyId = jdbc.queryForObject("""
                SELECT policy_version_id FROM fgc.policy_version
                 WHERE policy_code = 'STATUS-MONTH-RULE-2026' AND status = 'ACTIVE'
                """, Long.class);
        long contractId = contract("2026-03-01");
        statusEvent(contractId, 1, "ACTIVE", "2026-03-01T00:00:00+09:00");
        long lapse = statusEvent(contractId, 2, "LAPSED", "2026-09-01T00:00:00+09:00");
        long header = header(contractId);
        line(header, 1, "2026-08-25", "CONFIRMED");
        long lapsePeriodLine = line(header, 2, "2026-09-25", "PLANNED");
        long confirmedAfterLapse = line(header, 3, "2026-10-25", "CONFIRMED");
        long lockedLine = line(header, 4, "2026-11-25", "PLANNED");
        long futureLine = line(header, 5, "2026-12-25", "PLANNED");
        finalizeMonth("2026-11-01"); // 롤백되므로 다른 시험에 확정 월이 남지 않는다
        payment(contractId, "2026-08-20", false);
        long lapsePeriodPayment = payment(contractId, "2026-09-20", false);
        long confirmedPayment = payment(contractId, "2026-09-21", true);
        long postRevivalPayment = payment(contractId, "2026-10-20", false);

        List<ContractStatusEffect> onLapse = rule.decide(events.findById(lapse).orElseThrow(), Set.of());

        assertThat(onLapse).extracting(ContractStatusEffect::entityType, ContractStatusEffect::entityId,
                        ContractStatusEffect::previousState, ContractStatusEffect::nextState,
                        ContractStatusEffect::policyVersionId, ContractStatusEffect::reasonCode)
                .containsExactly(
                        tuple(SCHEDULE_LINE, lapsePeriodLine, "PLANNED", "CANCELLED", policyId, "STATUS_INACTIVE"),
                        tuple(SCHEDULE_LINE, confirmedAfterLapse, "CONFIRMED", "EXCLUDED", policyId, "ALREADY_CONFIRMED"),
                        tuple(SCHEDULE_LINE, lockedLine, "PLANNED", "EXCLUDED", policyId, "MONTH_LOCKED"),
                        tuple(SCHEDULE_LINE, futureLine, "PLANNED", "CANCELLED", policyId, "STATUS_INACTIVE"),
                        tuple(PAYMENT, lapsePeriodPayment, "DRAFT", "HELD", policyId, "STATUS_INACTIVE"),
                        tuple(PAYMENT, confirmedPayment, "CONFIRMED", "EXCLUDED", policyId, "ALREADY_CONFIRMED"),
                        tuple(PAYMENT, postRevivalPayment, "DRAFT", "HELD", policyId, "STATUS_INACTIVE"));
        assertThat(onLapse).filteredOn(effect -> effect.entityId().equals(lapsePeriodLine))
                .extracting(ContractStatusEffect::amount).containsExactly(new BigDecimal("1000.00"));

        long revival = statusEvent(contractId, 3, "REVIVED", "2026-10-15T00:00:00+09:00");
        // 나중 사건이 기록돼도 실효 사건의 판단은 같다(즉시·일일·월 경로가 같은 사건으로 판단).
        assertThat(rule.decide(events.findById(lapse).orElseThrow(), Set.of())).isEqualTo(onLapse);

        // FUN-028이 실효 판단을 반영했다고 두고(회차 중단·지급 보류) 부활을 판단한다.
        jdbc.update("UPDATE fgc.schedule_line SET line_status = 'CANCELLED' WHERE schedule_line_id IN (?, ?)",
                lapsePeriodLine, futureLine);
        List<ContractStatusEffect> onRevival = rule.decide(events.findById(revival).orElseThrow(),
                Set.of(lapsePeriodPayment, postRevivalPayment));

        assertThat(onRevival).extracting(ContractStatusEffect::entityType, ContractStatusEffect::entityId,
                        ContractStatusEffect::previousState, ContractStatusEffect::nextState,
                        ContractStatusEffect::reasonCode)
                .containsExactly(tuple(SCHEDULE_LINE, futureLine, "CANCELLED", "PLANNED", "STATUS_ACTIVE"),
                        tuple(PAYMENT, postRevivalPayment, "DRAFT", "RELEASED", "STATUS_ACTIVE"));
    }

    private ObjectNode scheduleDraft(String from, String to) {
        ObjectNode draft = mapper.createObjectNode()
                .put("policyCode", "P648-" + UUID.randomUUID().toString().substring(0, 13))
                .put("policyName", "FUN-015 상태별 지급조건 시험")
                .put("policyType", "SCHEDULE_ELIGIBILITY")
                .put("sourceClass", "PROJECT_ASSUMPTION")
                .put("effectiveFrom", from)
                .put("effectiveTo", to);
        draft.putArray("regulationRefs").add("REG-03");
        draft.putArray("sourceRefs");
        draft.putArray("sourceDetails");
        draft.putArray("commissionRules");
        draft.putArray("feeRegimeRules");
        draft.putArray("clawbackRules");
        ArrayNode parameters = draft.putArray("parameters");
        parameters.addObject().put("key", "STATUS_MONTH_RULE").put("value", "PAY_IF_ACTIVE_ON_DUE_DATE")
                .put("description", "지급예정일 시점 상태로 회차 지급 여부를 정한다.");
        parameters.addObject().put("key", "PRORATION_RULE").put("value", "NO_PRORATION")
                .put("description", "일할계산하지 않는다.");
        parameters.addObject().put("key", "REVIVAL_BACKFILL_RULE").put("value", "NO_RETROACTIVE")
                .put("description", "부활해도 실효 기간 회차를 소급하지 않는다.");
        return draft;
    }

    private long contract(String contractDate) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.insurance_contract(insurer_id, product_offering_id, contract_no, contract_date, agent_id,
                    organization_id, premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                    payment_term_months, current_status, data_origin)
                SELECT insurer_id, product_offering_id, ?, ?::date, agent_id, organization_id, 100000, 100000, 100000,
                       120, 'ACTIVE', 'MANUAL'
                  FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1
                RETURNING contract_id
                """, Long.class, "P648-" + UUID.randomUUID(), contractDate);
    }

    private long statusEvent(long contractId, int eventSeq, String status, String effectiveAt) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.contract_status_event(contract_id, event_seq, new_status, effective_at, received_at,
                    source_system, source_event_key, data_origin)
                VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz, 'TEST-648', ?, 'MANUAL')
                RETURNING contract_status_event_id
                """, Long.class, contractId, eventSeq, status, effectiveAt, effectiveAt, UUID.randomUUID().toString());
    }

    private long header(long contractId) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.schedule_header(contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_purpose, schedule_regime)
                VALUES (?, 'GA_TO_FC', (SELECT min(policy_version_id) FROM fgc.policy_version), 1, 'OPERATIONAL', 'CURRENT')
                RETURNING schedule_header_id
                """, Long.class, contractId);
    }

    private long line(long headerId, int installment, String dueDate, String status) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.schedule_line(schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, basis_code, basis_amount, calculation_type, fixed_amount, expected_amount, line_status)
                VALUES (?, ?, ?, ?, ?::date, (SELECT min(commission_item_id) FROM fgc.commission_item),
                        'TEST', 1000, 'FIXED', 1000, 1000, ?)
                RETURNING schedule_line_id
                """, Long.class, headerId, installment, installment, installment, dueDate, status);
    }

    /** 확정 가능한 PRE_CONFIRM 실행을 V7 생명주기 순서대로 확정한다. MONTHLY의 월별 진행 중 유일키와 겹치지 않는다. */
    private void finalizeMonth(String month) {
        Long runId = jdbc.queryForObject("""
                INSERT INTO fgc.validation_run(validation_month, run_no, run_type)
                SELECT ?::date, COALESCE(MAX(run_no), 0) + 1, 'PRE_CONFIRM' FROM fgc.validation_run
                 WHERE validation_month = ?::date
                RETURNING validation_run_id
                """, Long.class, month, month);
        jdbc.update("UPDATE fgc.validation_run SET status = 'RUNNING', current_step = 1, started_at = now() "
                + "WHERE validation_run_id = ?", runId);
        jdbc.update("UPDATE fgc.validation_run SET status = 'COMPLETED', current_step = 8, completed_at = now() "
                + "WHERE validation_run_id = ?", runId);
        jdbc.update("UPDATE fgc.validation_run SET status = 'FINALIZED', current_step = 10, finalized_at = now() "
                + "WHERE validation_run_id = ?", runId);
    }

    private long payment(long contractId, String attributionDate, boolean confirmed) {
        Long id = jdbc.queryForObject("""
                INSERT INTO fgc.commission_transaction(payment_stage, source_type, source_business_key, commission_item_id,
                    settlement_month, amount, cashflow_type)
                VALUES ('INSURER_TO_GA', 'INSURER_STATEMENT', ?, (SELECT min(commission_item_id) FROM fgc.commission_item),
                        date_trunc('month', ?::date)::date, 5000, 'PAYMENT')
                RETURNING commission_transaction_id
                """, Long.class, "P648-" + UUID.randomUUID(), attributionDate);
        jdbc.update("""
                INSERT INTO fgc.transaction_attribution(commission_transaction_id, contract_id, attribution_date,
                    attribution_month, attributed_amount, inclusion_status_snapshot, attribution_method)
                VALUES (?, ?, ?::date, date_trunc('month', ?::date)::date, 5000, 'INCLUDED', 'DIRECT')
                """, id, contractId, attributionDate, attributionDate);
        if (confirmed) {
            jdbc.update("UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = ?", id);
        }
        return id;
    }

    private FgcUserDetails insertUser(String role) {
        String login = "p648-" + UUID.randomUUID();
        Long id = jdbc.queryForObject("""
                INSERT INTO fgc.app_user(login_id, password_hash, user_name, role_id)
                VALUES (?, 'unused', 'FUN-015 시험 사용자', (SELECT role_id FROM fgc.app_role WHERE role_code = ?))
                RETURNING user_id
                """, Long.class, login, role);
        return new FgcUserDetails(new AppUserView(id, login, "unused", "FUN-015 시험 사용자", role, "ACTIVE"), true, true);
    }

    private JsonNode create(ObjectNode draft) throws Exception {
        return write(HttpMethod.POST, "/api/v1/policies", draft, 201);
    }

    private JsonNode write(HttpMethod method, String path, JsonNode payload, int expected) throws Exception {
        MvcResult result = perform(method, path, payload, writer);
        assertThat(result.getResponse().getStatus()).withFailMessage(result.getResponse().getContentAsString())
                .isEqualTo(expected);
        return body(result).path("data");
    }

    private JsonNode action(long id, String action, JsonNode version, FgcUserDetails actor, int expected)
            throws Exception {
        MvcResult result = perform(HttpMethod.POST, "/api/v1/policies/" + id + "/actions",
                actionRequest(action, version), actor);
        assertThat(result.getResponse().getStatus()).withFailMessage(result.getResponse().getContentAsString())
                .isEqualTo(expected);
        return body(result).path("data");
    }

    private ObjectNode actionRequest(String action, JsonNode version) {
        return mapper.createObjectNode().put("actionType", action).put("reason", "FUN-015 시험")
                .put("expectedStatus", version.path("status").asText())
                .put("expectedUpdatedAt", version.path("updatedAt").asText());
    }

    private JsonNode writeError(HttpMethod method, String path, JsonNode payload, FgcUserDetails actor, int expected)
            throws Exception {
        MvcResult result = perform(method, path, payload, actor);
        assertThat(result.getResponse().getStatus()).withFailMessage(result.getResponse().getContentAsString())
                .isEqualTo(expected);
        return body(result).path("error");
    }

    private JsonNode detail(long id) throws Exception {
        return body(mvc.perform(get("/api/v1/policies/" + id).with(user(writer))).andReturn()).path("data");
    }

    private MvcResult perform(HttpMethod method, String path, JsonNode payload, FgcUserDetails actor) throws Exception {
        return mvc.perform(request(method, path).with(user(actor)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(payload)))
                .andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
