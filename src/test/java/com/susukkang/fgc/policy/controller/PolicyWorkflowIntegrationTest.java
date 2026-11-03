package com.susukkang.fgc.policy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.FeeComponentType;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.compare.repository.CompareRankingRepository;
import com.susukkang.fgc.compare.service.ClawbackRuleResolver;
import com.susukkang.fgc.policy.code.FeeRegimeReasonCode;
import com.susukkang.fgc.policy.service.FeeRegimeSchedulePolicyService;
import com.susukkang.fgc.policy.service.FeeRegimeRulesetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import java.util.List;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IF-API-79·80·81·75: 실제 PostgreSQL 트랜잭션과 HTTP 계약을 함께 검증한다.
 * 외부 테스트 트랜잭션으로 감싸지 않아 실패 시 부분 커밋·멱등키 누락·동시성 오류를 드러낸다.
 * test 프로필의 일회성 DB에서 UUID 업무키와 독립 보험회사/항목을 사용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PolicyWorkflowIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private com.susukkang.fgc.schedule.service.ScheduleService scheduleGenerator;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean(name = "policyClock") private Clock policyClock;
    @Autowired private FeeRegimeSchedulePolicyService schedulePolicies;
    @Autowired private FeeRegimeRulesetService limitPolicies;
    @Autowired private CompareRankingRepository comparisonPolicies;

    private FgcUserDetails writer;
    private FgcUserDetails systemWriter;
    private FgcUserDetails approver;
    private long insurerId;
    private long itemId;

    @BeforeEach
    void createIndependentActorsAndRuleScope() {
        when(policyClock.instant()).thenReturn(Instant.parse("1999-12-31T00:00:00Z"));
        when(policyClock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        writer = insertUser("SETTLEMENT");
        systemWriter = insertUser("SYSTEM_ADMIN");
        approver = insertUser("GA_ADMIN");
        insurerId = jdbc.queryForObject("""
                INSERT INTO fgc.insurer(insurer_code, insurer_name, insurer_type)
                VALUES (?, '정책 작성 API 검증 보험회사', 'LIFE') RETURNING insurer_id
                """, Long.class, "P647-" + UUID.randomUUID().toString().substring(0, 12));
        itemId = jdbc.queryForObject("""
                INSERT INTO fgc.commission_item(item_code, item_name, cashflow_type, item_category,
                                                effective_from, active_yn)
                VALUES (?, '정책 작성 API 검증 수수료', 'PAYMENT', 'SALES', DATE '2026-01-01', true)
                RETURNING commission_item_id
                """, Long.class, "P647-" + UUID.randomUUID());
    }

    @Test
    void insurerSpecificClawbackCoexistsWithGenericDemoButIdenticalScopeOverlapIsRejected() throws Exception {
        ObjectNode draft = draft();
        draft.put("policyType", "CLAWBACK").putNull("feeRegimeCode")
                .put("effectiveFrom", "2026-01-01").put("effectiveTo", "2026-12-31");
        draft.putArray("commissionRules");
        long baseItem = jdbc.queryForObject("SELECT commission_item_id FROM fgc.commission_item WHERE item_code='BASE_COMMISSION'", Long.class);
        var rules = draft.putArray("clawbackRules");
        rules.addObject().put("insurerId", insurerId).put("commissionItemId", baseItem)
                .put("elapsedMonthFrom", 0).put("elapsedMonthTo", 11)
                .put("clawbackBasisCode", "PAID_ATTRIBUTED_AMOUNT").put("clawbackRatePct", "50");
        rules.addObject().put("insurerId", insurerId).put("commissionItemId", baseItem)
                .put("elapsedMonthFrom", 12).put("elapsedMonthTo", 23)
                .put("clawbackBasisCode", "PAID_ATTRIBUTED_AMOUNT").put("clawbackRatePct", "25");
        JsonNode first = create(draft, writer);
        long id = first.path("policyVersionId").asLong();
        JsonNode review = action(id, "SUBMIT", first, writer, 200);
        JsonNode approved = action(id, "APPROVE", review, approver, 200);
        action(id, "ACTIVATE", approved, approver, 200);

        ObjectNode duplicate = draft.deepCopy().put("policyCode", "C722-" + UUID.randomUUID());
        JsonNode second = create(duplicate, writer);
        long secondId = second.path("policyVersionId").asLong();
        JsonNode secondReview = action(secondId, "SUBMIT", second, writer, 200);
        assertThat(actionError(secondId, "APPROVE", secondReview, approver, 422).path("code").asText())
                .isEqualTo("FGC-POL-002");
        // FGC-FUN-017/079: 상품 비교는 보험회사·판매버전으로 정책을 고른 뒤 그 안에서 항목을 푼다.
        // 전체 항목이나 다른 항목으로 바꾸어도 같은 정책 선택 범위의 겹침을 우회할 수 없다.
        for (Long alternativeItem : new Long[]{null, itemId}) {
            ObjectNode alternative = draft.deepCopy().put("policyCode", "C722-" + UUID.randomUUID());
            alternative.putArray("clawbackRules").addObject().put("insurerId", insurerId)
                    .put("commissionItemId", alternativeItem).put("elapsedMonthFrom", 0).put("elapsedMonthTo", 11)
                    .put("clawbackBasisCode", "PAID_ATTRIBUTED_AMOUNT").put("clawbackRatePct", "50");
            JsonNode alternativeCreated = create(alternative, writer);
            long alternativeId = alternativeCreated.path("policyVersionId").asLong();
            JsonNode alternativeReview = action(alternativeId, "SUBMIT", alternativeCreated, writer, 200);
            assertThat(actionError(alternativeId, "APPROVE", alternativeReview, approver, 422).path("code").asText())
                    .isEqualTo("FGC-POL-002");
        }
        ObjectNode adjacent = draft.deepCopy().put("policyCode", "C722-" + UUID.randomUUID());
        adjacent.putArray("clawbackRules").addObject().put("insurerId", insurerId).put("commissionItemId", baseItem)
                .put("elapsedMonthFrom", 24).put("elapsedMonthTo", 35)
                .put("clawbackBasisCode", "PAID_ATTRIBUTED_AMOUNT").put("clawbackRatePct", "0");
        JsonNode adjacentCreated = create(adjacent, writer);
        long adjacentId = adjacentCreated.path("policyVersionId").asLong();
        JsonNode adjacentReview = action(adjacentId, "SUBMIT", adjacentCreated, writer, 200);
        assertThat(actionError(adjacentId, "APPROVE", adjacentReview, approver, 422).path("code").asText())
                .isEqualTo("FGC-POL-002");
        long otherInsurer = jdbc.queryForObject("""
                INSERT INTO fgc.insurer(insurer_code,insurer_name,insurer_type)
                VALUES (?, '다른 보험회사 범위', 'LIFE') RETURNING insurer_id
                """, Long.class, "P722-" + UUID.randomUUID().toString().substring(0, 12));
        ObjectNode other = draft.deepCopy().put("policyCode", "C722-" + UUID.randomUUID());
        other.path("clawbackRules").forEach(rule -> ((ObjectNode) rule).put("insurerId", otherInsurer));
        JsonNode third = create(other, writer);
        long thirdId = third.path("policyVersionId").asLong();
        JsonNode thirdReview = action(thirdId, "SUBMIT", third, writer, 200);
        JsonNode thirdApproved = action(thirdId, "APPROVE", thirdReview, approver, 200);
        action(thirdId, "ACTIVATE", thirdApproved, approver, 200);

        // 같은 정책 안의 인접 구간은 허용한다. 실제 판매버전으로 비교 SQL과 resolver의 선택 결과까지 확인한다.
        long offeringId = insertOffering(false, "2026-01-01", "BD-722");
        jdbc.update("""
                UPDATE fgc.product_offering SET fee_regime_code='CURRENT', governance_status='APPROVED',
                  committee_approval_date=DATE '2025-12-01', committee_document_ref='환수 비교 검증'
                WHERE product_offering_id=?
                """, offeringId);
        LocalDate asOf = LocalDate.of(2026, 6, 1);
        var offering = comparisonPolicies.findSellableOfferings(asOf).stream()
                .filter(candidate -> candidate.offeringId() == offeringId).findFirst().orElseThrow();
        var comparisonRules = comparisonPolicies.findClawbackRules(offering, asOf);
        for (int month : new int[]{0, 11, 12, 23}) {
            var resolution = ClawbackRuleResolver.resolve(comparisonRules, List.of(baseItem), month);
            assertThat(resolution.excludeReason()).isNull();
            assertThat(resolution.policyVersion()).isEqualTo(draft.path("policyCode").asText() + " v1");
            assertThat(resolution.ratesByItem().get(baseItem)).isEqualByComparingTo(month < 12 ? "50" : "25");
        }
    }

    @Test
    void clawbackDraftsPersistUpdateSubmitApproveActivateAndCloneWithoutChangingApprovedRules() throws Exception {
        ObjectNode draft = draft();
        draft.put("policyType", "CLAWBACK").putNull("feeRegimeCode");
        draft.putArray("commissionRules");
        ObjectNode rule = draft.putArray("clawbackRules").addObject();
        rule.put("insurerId", insurerId).put("commissionItemId", itemId)
                .put("elapsedMonthFrom", 0).put("elapsedMonthTo", 11)
                .put("clawbackBasisCode", "PAID_ATTRIBUTED_AMOUNT").put("clawbackRatePct", "33.333333");
        rule.putNull("conditionExpression");
        String requestKey = key();
        JsonNode created = write(HttpMethod.POST, "/api/v1/policies", draft, writer, requestKey, 201);
        assertThat(write(HttpMethod.POST, "/api/v1/policies", draft, writer, requestKey, 201)).isEqualTo(created);
        long id = created.path("policyVersionId").asLong();
        assertThat(count("clawback_rule", id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT condition_expression::text FROM fgc.clawback_rule WHERE policy_version_id=?",
                String.class, id)).isEqualTo("{}");
        ObjectNode edit = (ObjectNode) detail(id, writer).path("editDocument");
        assertThat(edit.path("clawbackRules").get(0).path("clawbackRatePct").asText()).isEqualTo("33.333333");
        ((ObjectNode) edit.path("clawbackRules").get(0)).put("clawbackRatePct", "0.000000");
        JsonNode updated = write(HttpMethod.PUT, "/api/v1/policies/" + id, edit, writer, key(), 200);
        assertThat(count("clawback_rule", id)).isEqualTo(1);
        assertThat(detail(id, writer).path("editDocument").path("clawbackRules").get(0)
                .path("clawbackRatePct").asText()).isEqualTo("0.000000");
        JsonNode review = action(id, "SUBMIT", updated, writer, 200);
        assertThat(values(detail(id, approver).path("allowedActions"))).contains("APPROVE");
        JsonNode approved = action(id, "APPROVE", review, approver, 200);
        JsonNode active = action(id, "ACTIVATE", approved, approver, 200);
        assertThat(active.path("status").asText()).isEqualTo("ACTIVE");
        long originalRuleId = jdbc.queryForObject("SELECT clawback_rule_id FROM fgc.clawback_rule WHERE policy_version_id=?", Long.class, id);
        JsonNode cloned = write(HttpMethod.POST, "/api/v1/policies/" + id + "/versions",
                cloneRequest("2000-06-01"), writer, key(), 201);
        long cloneId = cloned.path("policyVersionId").asLong();
        assertThat(count("clawback_rule", cloneId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT clawback_rule_id FROM fgc.clawback_rule WHERE policy_version_id=?", Long.class, cloneId))
                .isNotEqualTo(originalRuleId);
        ObjectNode cloneEdit = (ObjectNode) detail(cloneId, writer).path("editDocument");
        ((ObjectNode) cloneEdit.path("clawbackRules").get(0)).put("clawbackRatePct", "50.000000");
        write(HttpMethod.PUT, "/api/v1/policies/" + cloneId, cloneEdit, writer, key(), 200);
        assertThat(detail(id, writer).path("editDocument").path("clawbackRules").get(0)
                .path("clawbackRatePct").asText()).isEqualTo("0.000000");
        assertThat(count("policy_approval_action", cloneId)).isZero();
    }

    @Test
    void clawbackInvalidRulesRollbackAndEmptyRulesCannotBeSubmitted() throws Exception {
        ObjectNode draft = draft();
        draft.put("policyType", "CLAWBACK").putNull("feeRegimeCode");
        draft.putArray("commissionRules");
        ObjectNode rule = draft.putArray("clawbackRules").addObject();
        rule.put("elapsedMonthFrom", 12).put("elapsedMonthTo", 11)
                .put("clawbackBasisCode", "PAID_ATTRIBUTED_AMOUNT").put("clawbackRatePct", "50");
        JsonNode error = writeError(HttpMethod.POST, "/api/v1/policies", draft, writer, key(), 400);
        assertThat(error.path("field").asText()).isEqualTo("clawbackRules[0].elapsedMonthTo");
        rule.put("elapsedMonthTo", 23).putObject("conditionExpression").put("execute", "anything");
        writeError(HttpMethod.POST, "/api/v1/policies", draft, writer, key(), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.policy_version WHERE policy_code=?",
                Integer.class, draft.path("policyCode").asText())).isZero();
        draft.putArray("clawbackRules");
        JsonNode empty = create(draft, writer);
        assertThat(actionError(empty.path("policyVersionId").asLong(), "SUBMIT", empty, writer, 422)
                .path("field").asText()).isEqualTo("clawbackRules");
    }

    @ParameterizedTest
    @ValueSource(strings = {"insurerId", "productOfferingId", "commissionItemId"})
    void missingClawbackTargetsReturnInputErrorsAndRollback(String field) throws Exception {
        ObjectNode draft = clawbackDraft();
        ObjectNode rule = (ObjectNode) draft.path("clawbackRules").get(0);
        rule.put(field, Long.MAX_VALUE);
        String requestKey = key();
        JsonNode failure = writeError(HttpMethod.POST, "/api/v1/policies", draft, writer, requestKey, 400);
        assertThat(failure.path("code").asText()).isEqualTo("FGC-POL-005");
        assertThat(failure.path("message").asText()).contains("존재하지 않습니다").doesNotContain("{", "500");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.policy_version WHERE policy_code=?",
                Integer.class, draft.path("policyCode").asText())).isZero();

        rule.putNull(field);
        JsonNode created = write(HttpMethod.POST, "/api/v1/policies", draft, writer, requestKey, 201);
        long id = created.path("policyVersionId").asLong();
        ObjectNode edit = (ObjectNode) detail(id, writer).path("editDocument");
        JsonNode originalRules = edit.path("clawbackRules").deepCopy();
        ((ObjectNode) edit.path("clawbackRules").get(0)).put(field, Long.MAX_VALUE);
        assertThat(writeError(HttpMethod.PUT, "/api/v1/policies/" + id, edit, writer, key(), 400)
                .path("code").asText()).isEqualTo("FGC-POL-005");
        JsonNode unchanged = detail(id, writer).path("editDocument");
        assertThat(unchanged.path("clawbackRules")).isEqualTo(originalRules);
        assertThat(unchanged.path("expectedUpdatedAt")).isEqualTo(edit.path("expectedUpdatedAt"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sourceRefs", "clawbackRules"})
    void missingSubmissionRequirementsHaveSpecificMessagesInsteadOfOverlap(String field) throws Exception {
        ObjectNode draft = clawbackDraft();
        draft.putArray(field);
        if (field.equals("sourceRefs")) {
            draft.putArray("regulationRefs");
            draft.putArray("sourceDetails");
        }
        JsonNode created = create(draft, writer);
        long id = created.path("policyVersionId").asLong();
        JsonNode failure = actionError(id, "SUBMIT", created, writer, 422);
        assertThat(failure.path("code").asText()).isEqualTo("FGC-POL-004");
        assertThat(failure.path("field").asText()).isEqualTo(field);
        assertThat(failure.path("message").asText()).contains(field.equals("sourceRefs") ? "근거" : "규칙")
                .doesNotContain("겹치", "{reason}");
        assertThat(detail(id, writer).path("header").path("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void clawbackEditsPreserveRuleIdsAndCreationTimesWhileClonesReceiveNewIds() throws Exception {
        // FGC-FUN-017 (#647): 전체 PUT은 유지한 환수 규칙의 ID·생성시각을 보존하고, 두 구간을 맞바꿔도 유일키에 걸리지 않는다.
        JsonNode created = create(twoBandClawbackDraft(), systemWriter);
        long id = created.path("policyVersionId").asLong();
        var identities = clawbackIdentities(id);
        ObjectNode edit = (ObjectNode) detail(id, systemWriter).path("editDocument");
        ObjectNode first = (ObjectNode) edit.path("clawbackRules").get(0);
        ObjectNode second = (ObjectNode) edit.path("clawbackRules").get(1);
        assertThat(first.path("clawbackRuleId").asLong()).isPositive();
        assertThat(first.path("clawbackRatePct").isTextual()).isTrue();
        first.put("elapsedMonthFrom", 12).put("elapsedMonthTo", 1199).put("clawbackRatePct", "0.000000");
        second.put("elapsedMonthFrom", 0).put("elapsedMonthTo", 11).put("clawbackRatePct", "33.333333");
        JsonNode updated = write(HttpMethod.PUT, "/api/v1/policies/" + id, edit, systemWriter, key(), 200);
        assertThat(clawbackIdentities(id)).isEqualTo(identities);
        JsonNode review = action(id, "SUBMIT", updated, systemWriter, 200);
        JsonNode approved = action(id, "APPROVE", review, approver, 200);
        action(id, "ACTIVATE", approved, approver, 200);

        JsonNode originalRules = detail(id, writer).path("editDocument").path("clawbackRules");
        JsonNode cloned = write(HttpMethod.POST, "/api/v1/policies/" + id + "/versions",
                cloneRequest("2001-01-01"), writer, key(), 201);
        long cloneId = cloned.path("policyVersionId").asLong();
        ObjectNode clonedEdit = (ObjectNode) detail(cloneId, writer).path("editDocument");
        for (JsonNode rule : clonedEdit.path("clawbackRules")) {
            assertThat(identities).extracting(row -> row.get("clawback_rule_id"))
                    .isNotEmpty()
                    .doesNotContain(rule.path("clawbackRuleId").asLong());
        }
        ((ObjectNode) clonedEdit.path("clawbackRules").get(1)).put("clawbackRatePct", "25.000000");
        write(HttpMethod.PUT, "/api/v1/policies/" + cloneId, clonedEdit, writer, key(), 200);
        assertThat(detail(id, writer).path("editDocument").path("clawbackRules")).isEqualTo(originalRules);
    }

    @Test
    void clawbackRuleIdsMustBelongToTheEditedPolicyAndAreRejectedOnCreate() throws Exception {
        // FGC-FUN-017 (#647): 다른 정책의 환수 규칙 ID로 덮어쓰거나, 생성 요청에 기존 ID를 넣을 수 없다.
        JsonNode created = create(twoBandClawbackDraft(), writer);
        long id = created.path("policyVersionId").asLong();
        ObjectNode before = (ObjectNode) detail(id, writer).path("editDocument");
        long otherId = create(twoBandClawbackDraft(), writer).path("policyVersionId").asLong();
        long foreign = detail(otherId, writer).path("editDocument").path("clawbackRules").get(0)
                .path("clawbackRuleId").asLong();
        ObjectNode edit = before.deepCopy();
        ((ObjectNode) edit.path("clawbackRules").get(0)).put("clawbackRuleId", foreign);
        assertThat(writeError(HttpMethod.PUT, "/api/v1/policies/" + id, edit, writer, key(), 400)
                .path("field").asText()).isEqualTo("clawbackRules");
        ObjectNode duplicateIds = before.deepCopy();
        ((ObjectNode) duplicateIds.path("clawbackRules").get(1)).put("clawbackRuleId",
                before.path("clawbackRules").get(0).path("clawbackRuleId").asLong());
        assertThat(writeError(HttpMethod.PUT, "/api/v1/policies/" + id, duplicateIds, writer, key(), 400)
                .path("field").asText()).isEqualTo("clawbackRules[1].clawbackRuleId");
        ObjectNode invalidCreate = twoBandClawbackDraft();
        ((ObjectNode) invalidCreate.path("clawbackRules").get(0)).put("clawbackRuleId", foreign);
        writeError(HttpMethod.POST, "/api/v1/policies", invalidCreate, writer, key(), 400);
        assertThat(detail(id, writer).path("editDocument")).isEqualTo(before);
    }

    private ObjectNode twoBandClawbackDraft() {
        ObjectNode draft = clawbackDraft();
        ((ObjectNode) draft.path("clawbackRules").get(0)).put("clawbackRatePct", "33.333333");
        draft.withArray("clawbackRules").addObject().put("insurerId", insurerId).put("commissionItemId", itemId)
                .put("elapsedMonthFrom", 12).put("elapsedMonthTo", 1199)
                .put("clawbackBasisCode", "PAID_ATTRIBUTED_AMOUNT").put("clawbackRatePct", "0.000000");
        return draft;
    }

    private List<java.util.Map<String, Object>> clawbackIdentities(long id) {
        return jdbc.queryForList("SELECT clawback_rule_id, created_at FROM fgc.clawback_rule "
                + "WHERE policy_version_id=? ORDER BY clawback_rule_id", id);
    }

    @Test
    void preservesFixedAmountDecimalTextAcrossReadAndEdit() throws Exception {
        ObjectNode draft = draft();
        ObjectNode rule = (ObjectNode) draft.path("commissionRules").get(0);
        rule.put("calculationType", "FIXED").putNull("ratePct");
        rule.put("fixedAmount", "9999999999999.99");
        JsonNode created = create(draft, writer);
        long id = created.path("policyVersionId").asLong();
        ObjectNode edit = (ObjectNode) detail(id, writer).path("editDocument");
        JsonNode amount = edit.path("commissionRules").get(0).path("fixedAmount");
        assertThat(amount.isTextual()).isTrue();
        assertThat(amount.asText()).isEqualTo("9999999999999.99");
        edit.put("policyName", "정밀도를 보존한 고정금액 정책");
        write(HttpMethod.PUT, "/api/v1/policies/" + id, edit, writer, key(), 200);
        assertThat(detail(id, writer).path("editDocument").path("commissionRules").get(0)
                .path("fixedAmount").asText()).isEqualTo("9999999999999.99");
    }

    @Test
    void createsEditsSubmitsApprovesActivatesAndClonesWithHistoryAndSources() throws Exception {
        ObjectNode draft = draft();
        JsonNode created = create(draft, systemWriter);
        long id = created.path("policyVersionId").asLong();
        assertThat(created.path("versionNo").asInt()).isEqualTo(1);
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("approvedBy").isNull()).isTrue();
        assertThat(values(created.path("allowedActions"))).contains("SUBMIT");
        assertThat(jdbc.queryForObject("SELECT created_by FROM fgc.policy_version WHERE policy_version_id = ?",
                Long.class, id)).isEqualTo(systemWriter.getUserId());
        assertThat(count("commission_rule", id)).isEqualTo(1);
        assertThat(count("policy_source_detail", id)).isEqualTo(1);
        assertThat(auditCount(id, "POLICY_CREATE")).isEqualTo(1);

        ObjectNode edited = draft.deepCopy().put("policyName", "수정한 정책 초안")
                .put("expectedUpdatedAt", created.path("updatedAt").asText());
        JsonNode updated = write(HttpMethod.PUT, "/api/v1/policies/" + id, edited, systemWriter, key(), 200);
        assertThat(updated.path("updatedAt").asText()).isNotEqualTo(created.path("updatedAt").asText());
        assertThat(auditCount(id, "POLICY_UPDATE")).isEqualTo(1);

        JsonNode review = action(id, "SUBMIT", updated, systemWriter, 200);
        assertThat(review.path("status").asText()).isEqualTo("REVIEW");
        assertThat(values(detail(id, systemWriter).path("allowedActions"))).doesNotContain("APPROVE");
        assertThat(values(detail(id, approver).path("allowedActions"))).contains("APPROVE", "REJECT");

        ObjectNode reviewEdit = edited.deepCopy().put("expectedUpdatedAt", review.path("updatedAt").asText());
        JsonNode editError = writeError(HttpMethod.PUT, "/api/v1/policies/" + id,
                reviewEdit, systemWriter, key(), 409);
        assertThat(editError.path("code").asText()).isEqualTo("FGC-POL-003");
        JsonNode selfApproval = actionError(id, "APPROVE", review, systemWriter, 409);
        assertThat(selfApproval.path("code").asText()).isEqualTo("FGC-POL-001");
        assertThat(auditCount(id, "POLICY_APPROVE")).isZero();

        JsonNode approved = action(id, "APPROVE", review, approver, 200);
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("approvedBy").asText()).isEqualTo(approver.getUsername());
        assertThat(approved.path("approvedAt").isTextual()).isTrue();
        JsonNode active = action(id, "ACTIVATE", approved, approver, 200);
        assertThat(active.path("status").asText()).isEqualTo("ACTIVE");

        JsonNode detail = detail(id, writer);
        assertThat(detail.path("header").path("policyName").asText()).isEqualTo("수정한 정책 초안");
        assertThat(detail.path("commissionRules").size()).isEqualTo(1);
        assertThat(detail.path("editDocument").path("commissionRules").get(0).path("ratePct").isTextual()).isTrue();
        assertThat(detail.path("capRuleSets").isArray()).isTrue();
        assertThat(detail.path("refundRateTables").isArray()).isTrue();
        assertThat(detail.path("editDocument").path("sourceDetails").get(0).path("title").asText())
                .isEqualTo("승인된 근거 연결");
        assertThat(StreamSupport.stream(detail.path("approvalActions").spliterator(), false)
                .map(row -> row.path("actionType").asText()).toList())
                .containsExactly("SUBMIT", "APPROVE", "ACTIVATE");
        assertThat(StreamSupport.stream(detail.path("approvalActions").spliterator(), false)
                .map(row -> row.path("actionSeq").asInt()).toList()).containsExactly(1, 2, 3);
        assertThat(auditCount(id, "POLICY_SUBMIT")).isEqualTo(1);
        assertThat(auditCount(id, "POLICY_APPROVE")).isEqualTo(1);
        assertThat(auditCount(id, "POLICY_ACTIVATE")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fgc.audit_log
                WHERE policy_version_id = ? AND action_code IN ('POLICY_UPDATE', 'POLICY_SUBMIT',
                    'POLICY_APPROVE', 'POLICY_ACTIVATE') AND before_value IS NOT NULL AND after_value IS NOT NULL
                """, Integer.class, id)).isEqualTo(4);

        writeError(HttpMethod.PUT, "/api/v1/policies/" + id,
                edited.deepCopy().put("expectedUpdatedAt", active.path("updatedAt").asText()),
                systemWriter, key(), 409);
        JsonNode clone = write(HttpMethod.POST, "/api/v1/policies/" + id + "/versions",
                cloneRequest("2091-01-01"), writer, key(), 201);
        long cloneId = clone.path("policyVersionId").asLong();
        assertThat(cloneId).isNotEqualTo(id);
        assertThat(clone.path("versionNo").asInt()).isEqualTo(2);
        assertThat(clone.path("status").asText()).isEqualTo("DRAFT");
        assertThat(clone.path("approvedBy").isNull()).isTrue();
        assertThat(clone.path("approvedAt").isNull()).isTrue();
        assertThat(childId(cloneId)).isNotEqualTo(childId(id));
        assertThat(count("policy_source_detail", cloneId)).isEqualTo(1);
        assertThat(count("policy_approval_action", cloneId)).isZero();
        assertThat(auditCount(cloneId, "POLICY_CLONE")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT created_by FROM fgc.policy_version WHERE policy_version_id = ?",
                Long.class, cloneId)).isEqualTo(writer.getUserId());
        assertThat(statusOf(id)).isEqualTo("ACTIVE");
    }

    @Test
    void replaysCreateAndActionWithoutDuplicatesAndRejectsChangedOrUnauthorizedRequests() throws Exception {
        ObjectNode draft = draft();
        String createKey = key();
        JsonNode created = write(HttpMethod.POST, "/api/v1/policies", draft, writer, createKey, 201);
        long id = created.path("policyVersionId").asLong();
        assertThat(write(HttpMethod.POST, "/api/v1/policies", draft, writer, createKey, 201)).isEqualTo(created);
        writeError(HttpMethod.POST, "/api/v1/policies", draft.deepCopy().put("policyName", "다른 요청"),
                writer, createKey, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.policy_version WHERE policy_code = ?",
                Integer.class, draft.path("policyCode").asText())).isEqualTo(1);
        assertThat(count("commission_rule", id)).isEqualTo(1);
        assertThat(auditCount(id, "POLICY_CREATE")).isEqualTo(1);

        ObjectNode submit = actionRequest("SUBMIT", created);
        String submitKey = key();
        JsonNode submitted = write(HttpMethod.POST, "/api/v1/policies/" + id + "/actions",
                submit, writer, submitKey, 200);
        assertThat(write(HttpMethod.POST, "/api/v1/policies/" + id + "/actions",
                submit, writer, submitKey, 200)).isEqualTo(submitted);
        writeError(HttpMethod.POST, "/api/v1/policies/" + id + "/actions",
                submit.deepCopy().put("reason", "다른 사유"), writer, submitKey, 409);
        assertThat(count("policy_approval_action", id)).isEqualTo(1);
        assertThat(auditCount(id, "POLICY_SUBMIT")).isEqualTo(1);

        jdbc.update("UPDATE fgc.app_user SET role_id = (SELECT role_id FROM fgc.app_role WHERE role_code = 'COMPLIANCE') "
                + "WHERE user_id = ?", writer.getUserId());
        FgcUserDetails downgraded = new FgcUserDetails(new AppUserView(writer.getUserId(), writer.getUsername(),
                "unused", "권한 변경 사용자", "COMPLIANCE", "ACTIVE"), true, true);
        writeError(HttpMethod.POST, "/api/v1/policies", draft, downgraded, createKey, 403);
        assertThat(auditCount(id, "POLICY_CREATE")).isEqualTo(1);
    }

    @Test
    void rejectsStaleEditsAndOtherPolicyChildIdsWithoutChangingSavedRules() throws Exception {
        ObjectNode firstDraft = draft();
        JsonNode first = create(firstDraft, writer);
        long firstId = first.path("policyVersionId").asLong();
        JsonNode other = create(draft(), writer);
        long otherId = other.path("policyVersionId").asLong();
        ObjectNode update = firstDraft.deepCopy().put("policyName", "최신 수정")
                .put("expectedUpdatedAt", first.path("updatedAt").asText());
        JsonNode latest = write(HttpMethod.PUT, "/api/v1/policies/" + firstId, update, writer, key(), 200);
        JsonNode stale = writeError(HttpMethod.PUT, "/api/v1/policies/" + firstId,
                update.deepCopy().put("policyName", "오래된 요청"), writer, key(), 409);
        assertThat(stale.path("code").asText()).isEqualTo("FGC-POL-003");

        long ownRuleId = childId(firstId);
        long foreignRuleId = childId(otherId);
        ObjectNode foreignChild = update.deepCopy().put("expectedUpdatedAt", latest.path("updatedAt").asText());
        ((ObjectNode) foreignChild.path("commissionRules").get(0)).put("commissionRuleId", foreignRuleId);
        MvcResult rejected = perform(HttpMethod.PUT, "/api/v1/policies/" + firstId, foreignChild, writer, key());
        assertThat(rejected.getResponse().getStatus()).isBetween(400, 499);
        assertThat(childId(firstId)).isEqualTo(ownRuleId);
        assertThat(childId(otherId)).isEqualTo(foreignRuleId);
        assertThat(detail(firstId, writer).path("header").path("policyName").asText()).isEqualTo("최신 수정");
        assertThat(auditCount(firstId, "POLICY_UPDATE")).isEqualTo(1);
    }

    @Test
    void requiresAdministratorForApprovalAndRejectionThenMakesRejectedDraftEditable() throws Exception {
        ObjectNode draft = draft();
        JsonNode created = create(draft, writer);
        long id = created.path("policyVersionId").asLong();
        JsonNode premature = actionError(id, "ACTIVATE", created, approver, 409);
        assertThat(premature.path("code").asText()).isEqualTo("FGC-POL-003");
        JsonNode review = action(id, "SUBMIT", created, writer, 200);
        actionError(id, "APPROVE", review, writer, 403);
        actionError(id, "REJECT", review, writer, 403);
        JsonNode rejected = action(id, "REJECT", review, approver, 200);
        assertThat(rejected.path("status").asText()).isEqualTo("DRAFT");
        assertThat(rejected.path("approvedBy").isNull()).isTrue();
        assertThat(rejected.path("approvedAt").isNull()).isTrue();
        assertThat(values(detail(id, writer).path("allowedActions"))).contains("EDIT", "SUBMIT");
        assertThat(auditCount(id, "POLICY_REJECT")).isEqualTo(1);
        assertThat(auditCount(id, "POLICY_APPROVE")).isZero();
        assertThat(count("policy_approval_action", id)).isEqualTo(2);

        JsonNode updated = write(HttpMethod.PUT, "/api/v1/policies/" + id,
                draft.deepCopy().put("policyName", "반려 후 보완")
                        .put("expectedUpdatedAt", rejected.path("updatedAt").asText()), writer, key(), 200);
        assertThat(updated.path("status").asText()).isEqualTo("DRAFT");
        assertThat(detail(id, writer).path("header").path("policyName").asText()).isEqualTo("반려 후 보완");
    }

    @Test
    void rollsBackInvalidChildrenAndAllowsCorrectedRetryWithSameKey() throws Exception {
        ObjectNode draft = draft();
        String idempotencyKey = key();
        ((ObjectNode) draft.path("commissionRules").get(0)).put("commissionItemId", Long.MAX_VALUE);
        MvcResult failed = perform(HttpMethod.POST, "/api/v1/policies", draft, writer, idempotencyKey);
        assertThat(failed.getResponse().getStatus()).isBetween(400, 499);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.policy_version WHERE policy_code = ?",
                Integer.class, draft.path("policyCode").asText())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fgc.audit_log WHERE after_value->'document'->>'policyCode' = ?
                """, Integer.class, draft.path("policyCode").asText())).isZero();

        ((ObjectNode) draft.path("commissionRules").get(0)).put("commissionItemId", itemId);
        JsonNode corrected = write(HttpMethod.POST, "/api/v1/policies", draft, writer, idempotencyKey, 201);
        assertThat(count("commission_rule", corrected.path("policyVersionId").asLong())).isEqualTo(1);
    }

    @Test
    void atomicallyRetiresSameCodeAndRecordsBothTransitionsOnce() throws Exception {
        JsonNode created = create(draft(), writer);
        long id = created.path("policyVersionId").asLong();
        JsonNode review = action(id, "SUBMIT", created, writer, 200);
        JsonNode approved = action(id, "APPROVE", review, approver, 200);
        JsonNode active = action(id, "ACTIVATE", approved, approver, 200);
        JsonNode original = detail(id, writer);
        JsonNode clone = write(HttpMethod.POST, "/api/v1/policies/" + id + "/versions",
                cloneRequest("2000-07-01").put("effectiveTo", "2000-12-31"), writer, key(), 201);
        long cloneId = clone.path("policyVersionId").asLong();
        JsonNode cloneReview = action(cloneId, "SUBMIT", clone, writer, 200);
        JsonNode cloneApproved = action(cloneId, "APPROVE", cloneReview, approver, 200);
        assertThat(statusOf(id)).isEqualTo("ACTIVE"); // 승인만으로는 기존 버전을 종료하지 않는다.
        String activateKey = key();
        ObjectNode activate = actionRequest("ACTIVATE", cloneApproved);
        JsonNode successor = write(HttpMethod.POST, "/api/v1/policies/" + cloneId + "/actions", activate, approver, activateKey, 200);
        assertThat(write(HttpMethod.POST, "/api/v1/policies/" + cloneId + "/actions", activate, approver, activateKey, 200)).isEqualTo(successor);
        assertThat(statusOf(id)).isEqualTo("RETIRED");
        assertThat(statusOf(cloneId)).isEqualTo("ACTIVE");
        JsonNode retired = detail(id, writer);
        assertThat(retired.path("editDocument").path("commissionRules")).isEqualTo(original.path("editDocument").path("commissionRules"));
        assertThat(retired.path("editDocument").path("effectiveTo")).isEqualTo(original.path("editDocument").path("effectiveTo"));
        assertThat(retired.path("header").path("approvedAt")).isEqualTo(original.path("header").path("approvedAt"));
        assertThat(retired.path("approvalActions").get(3).path("evidenceRef").asText()).isEqualTo("policy:" + cloneId);
        assertThat(auditCount(id, "POLICY_RETIRE")).isEqualTo(1);
        assertThat(auditCount(cloneId, "POLICY_ACTIVATE")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.policy_succession WHERE previous_policy_version_id = ?",
                Integer.class, id)).isEqualTo(1);
        assertThat(retired.path("applicationPeriod").path("to").asText()).isEqualTo("2000-06-30");
        assertThat(active.path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void allocatesDistinctVersionsForConcurrentClones() throws Exception {
        JsonNode created = create(draft(), writer);
        long id = created.path("policyVersionId").asLong();
        JsonNode review = action(id, "SUBMIT", created, writer, 200);
        action(id, "APPROVE", review, approver, 200);

        List<MvcResult> results = concurrently(
                () -> perform(HttpMethod.POST, "/api/v1/policies/" + id + "/versions",
                        cloneRequest("2091-01-01"), writer, key()),
                () -> perform(HttpMethod.POST, "/api/v1/policies/" + id + "/versions",
                        cloneRequest("2092-01-01"), writer, key()));
        assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));
        JsonNode first = body(results.get(0)).path("data");
        JsonNode second = body(results.get(1)).path("data");
        assertThat(List.of(first.path("versionNo").asInt(), second.path("versionNo").asInt()))
                .containsExactlyInAnyOrder(2, 3);
        assertThat(first.path("policyVersionId").asLong()).isNotEqualTo(second.path("policyVersionId").asLong());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.policy_version WHERE policy_code = ?",
                Integer.class, created.path("policyCode").asText())).isEqualTo(3);
    }

    @Test
    void allowsOnlyOneConcurrentSubmitFromSameExpectedVersion() throws Exception {
        JsonNode created = create(draft(), writer);
        long id = created.path("policyVersionId").asLong();
        ObjectNode submit = actionRequest("SUBMIT", created);

        List<MvcResult> results = concurrently(
                () -> perform(HttpMethod.POST, "/api/v1/policies/" + id + "/actions", submit, writer, key()),
                () -> perform(HttpMethod.POST, "/api/v1/policies/" + id + "/actions", submit, writer, key()));
        assertThat(results.stream().map(result -> result.getResponse().getStatus()).toList())
                .containsExactlyInAnyOrder(200, 409);
        assertThat(statusOf(id)).isEqualTo("REVIEW");
        assertThat(count("policy_approval_action", id)).isEqualTo(1);
        assertThat(auditCount(id, "POLICY_SUBMIT")).isEqualTo(1);
    }

    @Test
    void rejectsOverlappingActiveScopeAcrossDifferentPolicyCodes() throws Exception {
        JsonNode first = create(draft(), writer);
        JsonNode second = create(draft(), writer);
        long firstId = first.path("policyVersionId").asLong();
        long secondId = second.path("policyVersionId").asLong();
        assertThat(first.path("policyCode").asText()).isNotEqualTo(second.path("policyCode").asText());
        JsonNode firstApproved = action(firstId, "APPROVE", action(firstId, "SUBMIT", first, writer, 200), approver, 200);
        JsonNode secondApproved = action(secondId, "APPROVE", action(secondId, "SUBMIT", second, writer, 200), approver, 200);
        JsonNode firstActive = action(firstId, "ACTIVATE", firstApproved, approver, 200);

        JsonNode rejected = actionError(secondId, "ACTIVATE", secondApproved, approver, 422);
        assertThat(rejected.path("code").asText()).isEqualTo("FGC-POL-002");
        assertThat(statusOf(firstId)).isEqualTo("ACTIVE");
        assertThat(statusOf(secondId)).isEqualTo("APPROVED");
        assertThat(detail(firstId, writer).path("editDocument").path("expectedUpdatedAt").asText())
                .isEqualTo(firstActive.path("updatedAt").asText());
        assertThat(count("policy_approval_action", secondId)).isEqualTo(2);
        assertThat(auditCount(secondId, "POLICY_ACTIVATE")).isZero();
    }

    @Test
    void writesAndActivatesFourYearRulesThenClonesWithNewChildIds() throws Exception {
        String documentVersion = "P647-DOC-" + UUID.randomUUID();
        ObjectNode draft = draft().put("policyType", "FOUR_YEAR_COMMISSION")
                .put("feeRegimeCode", "FOUR_YEAR_2027").put("basicDocumentVersion", documentVersion)
                .put("effectiveFrom", "2090-01-01").putNull("effectiveTo");
        ((ObjectNode) draft.path("commissionRules").get(0)).put("feeComponentType", "UPFRONT");
        ObjectNode feeRule = draft.withArray("feeRegimeRules").addObject();
        feeRule.put("feeRegimeCode", "FOUR_YEAR_2027");
        feeRule.put("salesStartDateFrom", "2027-01-01");
        feeRule.put("salesStartDateTo", "2028-12-31");
        feeRule.put("basicDocumentVersion", documentVersion);
        feeRule.putNull("basicDocumentDateFrom");
        feeRule.putNull("basicDocumentDateTo");
        feeRule.put("maxInstallments", 48);
        feeRule.putNull("longTermMaintenanceFrom");
        feeRule.putNull("longTermMaintenanceTo");
        feeRule.put("reviewRequiredYn", false);
        feeRule.putArray("sourceRefs").add("REG-03");
        JsonNode created = create(draft, writer);
        long id = created.path("policyVersionId").asLong();
        ObjectNode edit = (ObjectNode) detail(id, writer).path("editDocument").deepCopy();
        long feeId = edit.path("feeRegimeRules").get(0).path("feeRegimeRulesetId").asLong();
        long commissionId = edit.path("commissionRules").get(0).path("commissionRuleId").asLong();
        assertThat(feeId).isPositive();
        assertThat(count("fee_regime_ruleset", id)).isEqualTo(1);
        // 최대 허용 회차와 실제 지급 규칙 회차는 서로 독립적으로 저장한다.
        edit.put("basicDocumentVersion", documentVersion + "-R2");
        ((ObjectNode) edit.path("feeRegimeRules").get(0))
                .put("basicDocumentVersion", documentVersion + "-R2").put("maxInstallments", 36);
        ((ObjectNode) edit.path("commissionRules").get(0)).put("ratePct", "12.000000");
        JsonNode updated = write(HttpMethod.PUT, "/api/v1/policies/" + id, edit, writer, key(), 200);
        JsonNode saved = detail(id, writer).path("editDocument");
        assertThat(saved.path("feeRegimeRules").get(0).path("feeRegimeRulesetId").asLong()).isEqualTo(feeId);
        assertThat(saved.path("feeRegimeRules").get(0).path("maxInstallments").asInt()).isEqualTo(36);
        assertThat(saved.path("feeRegimeRules").get(0).path("basicDocumentVersion").asText())
                .isEqualTo(documentVersion + "-R2");
        assertThat(saved.path("commissionRules").get(0).path("commissionRuleId").asLong()).isEqualTo(commissionId);
        assertThat(saved.path("commissionRules").get(0).path("installmentTo").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.fee_regime_ruleset_limit WHERE fee_regime_ruleset_id = ?",
                Integer.class, feeId)).isZero();
        JsonNode review = action(id, "SUBMIT", updated, writer, 200);
        JsonNode approved = action(id, "APPROVE", review, approver, 200);
        JsonNode active = action(id, "ACTIVATE", approved, approver, 200);
        assertThat(active.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(auditCount(id, "POLICY_ACTIVATE")).isEqualTo(1);
        JsonNode clone = write(HttpMethod.POST, "/api/v1/policies/" + id + "/versions",
                cloneRequest("2091-01-01"), writer, key(), 201);
        JsonNode clonedDocument = detail(clone.path("policyVersionId").asLong(), writer).path("editDocument");
        assertThat(clonedDocument.path("feeRegimeRules").get(0).path("feeRegimeRulesetId").asLong()).isNotEqualTo(feeId);
        assertThat(clonedDocument.path("feeRegimeRules").get(0).path("maxInstallments").asInt()).isEqualTo(36);
        assertThat(clonedDocument.path("commissionRules").get(0).path("commissionRuleId").asLong()).isNotEqualTo(commissionId);
        assertThat(clone.path("approvedBy").isNull()).isTrue();
    }

    @Test
    void preservesInheritedLegacyEvidenceDuringCloneEditAndSubmissionWithoutAllowingNewRawRefs() throws Exception {
        Long sourceId = jdbc.queryForObject("""
                SELECT policy_version_id FROM fgc.policy_version
                WHERE policy_code = 'REG-FOUR-YEAR-2027-V1' AND version_no = 1
                """, Long.class);
        JsonNode original = detail(sourceId, writer).path("editDocument");
        String legacyRef = "docs/FGC_가상_GA_운영정책서_v1_0.md#제46조";
        assertThat(values(original.path("sourceRefs"))).contains(legacyRef);
        JsonNode clone = write(HttpMethod.POST, "/api/v1/policies/" + sourceId + "/versions",
                cloneRequest("2028-01-01"), writer, key(), 201);
        long cloneId = clone.path("policyVersionId").asLong();
        ObjectNode edit = (ObjectNode) detail(cloneId, writer).path("editDocument").deepCopy();
        JsonNode originalFeeRefs = edit.path("feeRegimeRules").get(0).path("sourceRefs").deepCopy();
        long feeId = edit.path("feeRegimeRules").get(0).path("feeRegimeRulesetId").asLong();
        int limitCount = jdbc.queryForObject("SELECT count(*) FROM fgc.fee_regime_ruleset_limit WHERE fee_regime_ruleset_id = ?",
                Integer.class, feeId);
        assertThat(limitCount).isPositive();
        ObjectNode commission = (ObjectNode) draft().path("commissionRules").get(0).deepCopy();
        commission.put("feeComponentType", "UPFRONT");
        edit.withArray("commissionRules").add(commission);
        ObjectNode inventedRef = edit.deepCopy();
        inventedRef.withArray("sourceRefs").add("unapproved/" + UUID.randomUUID());
        writeError(HttpMethod.PUT, "/api/v1/policies/" + cloneId, inventedRef, writer, key(), 400);
        JsonNode updated = write(HttpMethod.PUT, "/api/v1/policies/" + cloneId, edit, writer, key(), 200);
        JsonNode review = action(cloneId, "SUBMIT", updated, writer, 200);
        assertThat(review.path("status").asText()).isEqualTo("REVIEW");
        JsonNode saved = detail(cloneId, writer).path("editDocument");
        assertThat(saved.path("sourceRefs")).isEqualTo(original.path("sourceRefs"));
        assertThat(saved.path("feeRegimeRules").get(0).path("sourceRefs")).isEqualTo(originalFeeRefs);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.fee_regime_ruleset_limit WHERE fee_regime_ruleset_id = ?",
                Integer.class, feeId)).isEqualTo(limitCount);
        assertThat(detail(sourceId, writer).path("editDocument")).isEqualTo(original);
        ObjectNode newDraft = draft();
        newDraft.withArray("sourceRefs").add(legacyRef);
        writeError(HttpMethod.POST, "/api/v1/policies", newDraft, writer, key(), 400);
    }

    @Test
    void rejectsReaddedRegimeRowsThatLoseInheritedLimits() throws Exception {
        Long sourceId = jdbc.queryForObject("""
                SELECT policy_version_id FROM fgc.policy_version
                WHERE policy_code = 'REG-FOUR-YEAR-2027-V1' AND version_no = 1
                """, Long.class);
        JsonNode original = detail(sourceId, writer).path("editDocument");
        JsonNode copy = write(HttpMethod.POST, "/api/v1/policies/" + sourceId + "/versions",
                cloneRequest("2028-01-01"), writer, key(), 201);
        long copyId = copy.path("policyVersionId").asLong();
        ObjectNode edit = (ObjectNode) detail(copyId, writer).path("editDocument").deepCopy();
        long inheritedFeeId = edit.path("feeRegimeRules").get(0).path("feeRegimeRulesetId").asLong();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.fee_regime_ruleset_limit WHERE fee_regime_ruleset_id = ?",
                Integer.class, inheritedFeeId)).isPositive();
        ((ObjectNode) edit.path("feeRegimeRules").get(0)).remove("feeRegimeRulesetId");
        ObjectNode commission = (ObjectNode) draft().path("commissionRules").get(0).deepCopy();
        commission.put("feeComponentType", "UPFRONT");
        edit.withArray("commissionRules").add(commission);
        JsonNode updated = write(HttpMethod.PUT, "/api/v1/policies/" + copyId, edit, writer, key(), 200);
        JsonNode error = actionError(copyId, "SUBMIT", updated, writer, 400);
        assertThat(error.path("field").asText()).isEqualTo("feeRegimeRules");
        assertThat(error.path("detail").asText()).contains("한도");
        assertThat(detail(copyId, writer).path("header").path("status").asText()).isEqualTo("DRAFT");
        assertThat(detail(sourceId, writer).path("editDocument")).isEqualTo(original);
    }

    @Test
    void rejectsApprovalOverlappingExistingWildcardPolicyEvenForANewInsurer() throws Exception {
        ObjectNode draft = draft().put("effectiveFrom", "2090-01-01").put("effectiveTo", "2090-12-31");
        JsonNode created = create(draft, writer);
        long id = created.path("policyVersionId").asLong();
        JsonNode review = action(id, "SUBMIT", created, writer, 200);
        JsonNode rejected = actionError(id, "APPROVE", review, approver, 422);
        assertThat(rejected.path("code").asText()).isEqualTo("FGC-POL-002");
        assertThat(statusOf(id)).isEqualTo("REVIEW");
        assertThat(auditCount(id, "POLICY_APPROVE")).isZero();
    }

    private FgcUserDetails insertUser(String role) {
        String login = "p647-" + UUID.randomUUID();
        Long id = jdbc.queryForObject("""
                INSERT INTO fgc.app_user(login_id, password_hash, user_name, role_id)
                VALUES (?, 'unused', '정책 API 검증 사용자',
                        (SELECT role_id FROM fgc.app_role WHERE role_code = ?)) RETURNING user_id
                """, Long.class, login, role);
        return new FgcUserDetails(new AppUserView(id, login, "unused", "정책 API 검증 사용자", role, "ACTIVE"), true, true);
    }

    @Test
    void rejectsApprovalByAnyEditorEvenAfterRejectionAndAnotherWritersResubmission() throws Exception {
        JsonNode created = create(draft(), writer);
        long id = created.path("policyVersionId").asLong();
        ObjectNode edited = (ObjectNode) detail(id, writer).path("editDocument").deepCopy();
        edited.put("policyName", "다른 관리자가 보완한 초안");
        JsonNode updated = write(HttpMethod.PUT, "/api/v1/policies/" + id, edited, approver, key(), 200);
        JsonNode review = action(id, "SUBMIT", updated, writer, 200);
        assertThat(values(detail(id, approver).path("allowedActions"))).containsExactly("REJECT");
        assertThat(actionError(id, "APPROVE", review, approver, 409).path("code").asText()).isEqualTo("FGC-POL-001");
        JsonNode rejected = action(id, "REJECT", review, systemWriter, 200);
        JsonNode resubmitted = action(id, "SUBMIT", rejected, writer, 200);
        assertThat(actionError(id, "APPROVE", resubmitted, approver, 409).path("code").asText()).isEqualTo("FGC-POL-001");
        assertThat(auditCount(id, "POLICY_APPROVE")).isZero();
        assertThat(action(id, "APPROVE", resubmitted, systemWriter, 200).path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void treatsTheSubmitterAsAnAuthorForApprovalPermissions() throws Exception {
        JsonNode created = create(draft(), writer);
        long id = created.path("policyVersionId").asLong();
        JsonNode review = action(id, "SUBMIT", created, systemWriter, 200);
        assertThat(values(review.path("allowedActions"))).containsExactly("REJECT");
        assertThat(actionError(id, "APPROVE", review, systemWriter, 409).path("code").asText()).isEqualTo("FGC-POL-001");
        assertThat(action(id, "APPROVE", review, approver, 200).path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void rejectsLongWhitespaceReferenceWithoutBacktrackingAndKeepsDetailedReferencesSupported() throws Exception {
        ObjectNode malformed = draft();
        malformed.withArray("sourceRefs").add("SRC-022" + " ".repeat(50_000) + "\n");
        JsonNode error = assertTimeout(Duration.ofSeconds(5), () ->
                writeError(HttpMethod.POST, "/api/v1/policies", malformed, writer, key(), 400));
        assertThat(error.path("field").asText()).isEqualTo("sourceRefs");
        ObjectNode valid = draft();
        valid.withArray("sourceRefs").add("SRC-022 Q11");
        assertThat(create(valid, writer).path("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void acceptsRegistryCodesNeverUsedByApprovedPoliciesButRejectsUnknownCodes() throws Exception {
        // FUN-016: 규제조문표의 REG-17은 승인 정책에 쓰인 적이 없어도 레지스트리(V71)에 있으므로 처음 등록할 수 있다.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fgc.policy_version
                WHERE status IN ('APPROVED','ACTIVE','RETIRED') AND 'REG-17' = ANY (regulation_refs)
                """, Integer.class)).isZero();
        ObjectNode registered = draft();
        registered.withArray("regulationRefs").add("REG-17");
        assertThat(create(registered, writer).path("status").asText()).isEqualTo("DRAFT");
        ObjectNode unknown = draft();
        unknown.withArray("regulationRefs").add("REG-99");
        assertThat(writeError(HttpMethod.POST, "/api/v1/policies", unknown, writer, key(), 400).path("field").asText())
                .isEqualTo("regulationRefs");
    }

    @Test
    void acceptsOnlyWebLinksAndTextOnlySourceDetails() throws Exception {
        for (String unsafe : List.of("javascript:alert(1)", "ftp://example.com/a", "file:///etc/passwd",
                "https://user:pw@example.com/a", " https://example.com/a", "/relative/path", "https://")) {
            ObjectNode draft = draft();
            ((ObjectNode) draft.withArray("sourceDetails").get(0)).put("url", unsafe);
            assertThat(writeError(HttpMethod.POST, "/api/v1/policies", draft, writer, key(), 400).path("field").asText())
                    .as(unsafe).isEqualTo("sourceDetails[0].url");
        }
        ObjectNode textOnly = draft();
        ((ObjectNode) textOnly.withArray("sourceDetails").get(0)).putNull("url");
        long id = create(textOnly, writer).path("policyVersionId").asLong();
        JsonNode source = StreamSupport.stream(detail(id, writer).path("references").spliterator(), false)
                .filter(row -> row.path("code").asText().equals("SRC-022")).findFirst().orElseThrow();
        assertThat(source.path("detailTitle").asText()).isEqualTo("승인된 근거 연결");
        assertThat(source.path("url").isNull()).isTrue();
    }

    @Test
    void detailMergesRegistryTitlesWithTheDetailsStoredOnThatPolicyVersion() throws Exception {
        long id = create(draft(), writer).path("policyVersionId").asLong();
        JsonNode references = detail(id, writer).path("references");
        JsonNode regulation = references.get(0);
        assertThat(regulation.path("ref").asText()).isEqualTo("REG-03");
        assertThat(regulation.path("group").asText()).isEqualTo("REGULATION");
        assertThat(regulation.path("registryTitle").asText()).isEqualTo("유지관리수수료");
        // 상세를 저장하지 않은 근거는 공통 샘플 설명으로 채우지 않는다.
        assertThat(regulation.path("detailTitle").isNull()).isTrue();
        assertThat(regulation.path("explanation").isNull()).isTrue();
        JsonNode source = StreamSupport.stream(references.spliterator(), false)
                .filter(row -> row.path("group").asText().equals("SOURCE")).findFirst().orElseThrow();
        assertThat(source.path("code").asText()).isEqualTo("SRC-022");
        assertThat(source.path("registryTitle").isNull()).isTrue();
        assertThat(source.path("detailTitle").asText()).isEqualTo("승인된 근거 연결");
        assertThat(source.path("explanation").asText()).isEqualTo("정책 등록과 복제에서 근거를 보존한다.");
        assertThat(source.path("url").asText()).isEqualTo("https://example.com/policy-evidence");
        assertThat(values(regulation.path("statusTags"))).isEmpty();
    }

    @Test
    void rejectsNullStatusTagAsAFieldErrorInsteadOfFailingDeserialization() throws Exception {
        ObjectNode draft = draft();
        ((ObjectNode) draft.withArray("sourceDetails").get(0)).putArray("statusTags").addNull();
        assertThat(writeError(HttpMethod.POST, "/api/v1/policies", draft, writer, key(), 400).path("field").asText())
                .startsWith("sourceDetails[0].statusTags");
    }

    @Test
    void aStoredLegacyReferenceTextIsNotAcceptedAsANewLink() throws Exception {
        Long sourceId = jdbc.queryForObject("""
                SELECT policy_version_id FROM fgc.policy_version
                WHERE policy_code = 'REG-FOUR-YEAR-2027-V1' AND version_no = 1
                """, Long.class);
        String legacyRef = "docs/FGC_가상_GA_운영정책서_v1_0.md#제46조";
        long cloneId = write(HttpMethod.POST, "/api/v1/policies/" + sourceId + "/versions",
                cloneRequest("2028-01-01"), writer, key(), 201).path("policyVersionId").asLong();
        ObjectNode edit = (ObjectNode) detail(cloneId, writer).path("editDocument").deepCopy();
        assertThat(values(edit.path("sourceRefs"))).contains(legacyRef);
        // 근거 코드로 물려받은 문서명 참조와 글자가 같아도, 링크로는 새 입력이라 http(s) 검사를 받는다.
        int index = edit.withArray("sourceDetails").size();
        edit.withArray("sourceDetails").addObject().put("ref", legacyRef).put("title", "옛 근거")
                .putNull("explanation").put("url", legacyRef);
        assertThat(writeError(HttpMethod.PUT, "/api/v1/policies/" + cloneId, edit, writer, key(), 400)
                .path("field").asText()).isEqualTo("sourceDetails[" + index + "].url");
    }

    @Test
    void keepsAnAlreadyStoredNonWebLinkButRejectsANewOne() throws Exception {
        long id = create(draft(), writer).path("policyVersionId").asLong();
        // URL 검사가 생기기 전에 저장된 링크를 흉내 낸다(초안 상태라 DRAFT 전용 트리거가 허용한다).
        jdbc.update("UPDATE fgc.policy_source_detail SET url = 'ftp://legacy.example.com/a' WHERE policy_version_id = ?", id);
        ObjectNode edit = (ObjectNode) detail(id, writer).path("editDocument").deepCopy();
        // 같은 링크를 다른 근거(REG-03)로 옮기면 물려받은 링크로 보지 않는다.
        ObjectNode moved = edit.deepCopy();
        ((ObjectNode) moved.withArray("sourceDetails").get(0)).putNull("url");
        moved.withArray("sourceDetails").addObject().put("ref", "REG-03").put("title", "옮긴 링크")
                .putNull("explanation").put("url", "ftp://legacy.example.com/a");
        assertThat(writeError(HttpMethod.PUT, "/api/v1/policies/" + id, moved, writer, key(), 400)
                .path("field").asText()).isEqualTo("sourceDetails[1].url");
        JsonNode updated = write(HttpMethod.PUT, "/api/v1/policies/" + id, edit, writer, key(), 200);
        assertThat(action(id, "SUBMIT", updated, writer, 200).path("status").asText()).isEqualTo("REVIEW");

        long other = create(draft(), writer).path("policyVersionId").asLong();
        ObjectNode changed = (ObjectNode) detail(other, writer).path("editDocument").deepCopy();
        ((ObjectNode) changed.withArray("sourceDetails").get(0)).put("url", "ftp://new.example.com/a");
        assertThat(writeError(HttpMethod.PUT, "/api/v1/policies/" + other, changed, writer, key(), 400)
                .path("field").asText()).isEqualTo("sourceDetails[0].url");
    }

    @Test
    void detailShowsCommitteeMetadataOfTheOfferingsItsRulesTarget() throws Exception {
        long product = jdbc.queryForObject("""
                INSERT INTO fgc.product(insurer_id, insurer_product_code, standard_product_code, product_name, product_group_code)
                VALUES (?, ?, ?, '상품위원회 검증 상품', 'TERM') RETURNING product_id
                """, Long.class, insurerId, "P718-" + UUID.randomUUID().toString().substring(0, 12),
                "S718-" + UUID.randomUUID().toString().substring(0, 12));
        long offering = jdbc.queryForObject("""
                INSERT INTO fgc.product_offering(product_id, offering_version, sales_start_date, basic_document_version,
                    basic_document_date, channel_code, fee_regime_code, committee_approval_date, committee_document_ref,
                    governance_status)
                VALUES (?, 'V1', DATE '2000-01-01', 'BD-718', DATE '1999-12-01', 'FACE_TO_FACE', 'CURRENT',
                        DATE '1999-12-15', '상품위원회 의결서 2026-07', 'APPROVED') RETURNING product_offering_id
                """, Long.class, product);
        ObjectNode draft = draft();
        ((ObjectNode) draft.path("commissionRules").get(0)).put("productOfferingId", offering);
        long id = create(draft, writer).path("policyVersionId").asLong();
        JsonNode offerings = detail(id, writer).path("productOfferings");
        assertThat(offerings).hasSize(1);
        JsonNode row = offerings.get(0);
        assertThat(row.path("productOfferingId").asLong()).isEqualTo(offering);
        assertThat(row.path("committeeApprovalDate").asText()).isEqualTo("1999-12-15");
        assertThat(row.path("committeeDocumentRef").asText()).isEqualTo("상품위원회 의결서 2026-07");
        assertThat(row.path("basicDocumentVersion").asText()).isEqualTo("BD-718");
        assertThat(row.path("salesStartDate").asText()).isEqualTo("2000-01-01");
        assertThat(row.path("governanceStatus").asText()).isEqualTo("APPROVED");
    }

    @Test
    void storesSourceStatusTagsAndKeepsThemThroughEditAndNewVersion() throws Exception {
        ObjectNode tagged = draft();
        ((ObjectNode) tagged.withArray("sourceDetails").get(0)).putArray("statusTags").add("현행규정").add("FAQ").add("FAQ");
        JsonNode created = create(tagged, writer);
        long id = created.path("policyVersionId").asLong();
        JsonNode saved = detail(id, writer);
        assertThat(values(saved.path("editDocument").path("sourceDetails").get(0).path("statusTags")))
                .containsExactly("현행규정", "FAQ");
        JsonNode source = StreamSupport.stream(saved.path("references").spliterator(), false)
                .filter(row -> row.path("code").asText().equals("SRC-022")).findFirst().orElseThrow();
        assertThat(values(source.path("statusTags"))).containsExactly("현행규정", "FAQ");

        ObjectNode unknown = draft();
        ((ObjectNode) unknown.withArray("sourceDetails").get(0)).putArray("statusTags").add("보도자료");
        assertThat(writeError(HttpMethod.POST, "/api/v1/policies", unknown, writer, key(), 400).path("field").asText())
                .isEqualTo("sourceDetails[0].statusTags");

        // 새 버전은 승인본에서만 만들 수 있다. 승인본의 태그는 DRAFT 전용 트리거로 바꿀 수 없고 복제본이 이어받는다.
        JsonNode review = action(id, "SUBMIT", created, writer, 200);
        action(id, "APPROVE", review, approver, 200);
        JsonNode clone = write(HttpMethod.POST, "/api/v1/policies/" + id + "/versions",
                cloneRequest("2000-06-01"), writer, key(), 201);
        assertThat(values(detail(clone.path("policyVersionId").asLong(), writer)
                .path("editDocument").path("sourceDetails").get(0).path("statusTags")))
                .containsExactly("현행규정", "FAQ");
    }

    @Test
    void storesApprovalEvidenceWithItsDocumentVersionAndResetsItOnClone() throws Exception {
        JsonNode created = create(draft().put("basicDocumentVersion", "DOC-V1"), writer);
        long id = created.path("policyVersionId").asLong();
        ObjectNode submit = actionRequest("SUBMIT", created).put("evidenceRef", "의결서-001");
        assertThat(writeError(HttpMethod.POST, "/api/v1/policies/" + id + "/actions", submit, writer, key(), 400)
                .path("field").asText()).isEqualTo("evidenceRef");
        JsonNode review = action(id, "SUBMIT", created, writer, 200);
        ObjectNode approval = actionRequest("APPROVE", review).put("evidenceRef", "\u2003 의결서-001 \u2003");
        JsonNode approved = write(HttpMethod.POST, "/api/v1/policies/" + id + "/actions", approval, approver, key(), 200);
        action(id, "ACTIVATE", approved, approver, 200);
        JsonNode active = detail(id, writer);
        assertThat(active.path("approvalEvidenceRef").asText()).isEqualTo("의결서-001");
        assertThat(active.path("editDocument").path("basicDocumentVersion").asText()).isEqualTo("DOC-V1");
        assertThat(active.path("approvalActions").get(1).path("evidenceRef").asText()).isEqualTo("의결서-001");
        JsonNode clone = write(HttpMethod.POST, "/api/v1/policies/" + id + "/versions",
                cloneRequest("2001-01-01"), writer, key(), 201);
        assertThat(detail(clone.path("policyVersionId").asLong(), writer).path("approvalEvidenceRef").isNull()).isTrue();
    }

    @Test
    void rollsBackRetirementWhenAnotherCodeConflictsWithTheReplacement() throws Exception {
        when(policyClock.instant()).thenReturn(Instant.parse("1799-12-31T00:00:00Z"));
        JsonNode first = create(draft().put("effectiveFrom", "1800-01-01").put("effectiveTo", "1800-12-31"), writer);
        long firstId = first.path("policyVersionId").asLong();
        action(firstId, "ACTIVATE", action(firstId, "APPROVE", action(firstId, "SUBMIT", first, writer, 200), approver, 200), approver, 200);
        JsonNode copy = write(HttpMethod.POST, "/api/v1/policies/" + firstId + "/versions",
                cloneRequest("1800-07-01").put("effectiveTo", "1800-12-31"), writer, key(), 201);
        long copyId = copy.path("policyVersionId").asLong();
        long otherInsurer = jdbc.queryForObject("""
                INSERT INTO fgc.insurer(insurer_code, insurer_name, insurer_type) VALUES (?, '별도 보험사', 'LIFE') RETURNING insurer_id
                """, Long.class, "P647-" + UUID.randomUUID().toString().substring(0, 12));
        ObjectNode edit = (ObjectNode) detail(copyId, writer).path("editDocument").deepCopy();
        ObjectNode extraScope = ((ObjectNode) edit.path("commissionRules").get(0)).deepCopy().put("insurerId", otherInsurer);
        extraScope.remove("commissionRuleId");
        edit.withArray("commissionRules").add(extraScope);
        JsonNode updated = write(HttpMethod.PUT, "/api/v1/policies/" + copyId, edit, writer, key(), 200);
        JsonNode approved = action(copyId, "APPROVE", action(copyId, "SUBMIT", updated, writer, 200), approver, 200);
        ObjectNode otherDraft = draft().put("effectiveFrom", "1800-01-01").put("effectiveTo", "1800-12-31");
        ((ObjectNode) otherDraft.path("commissionRules").get(0)).put("insurerId", otherInsurer);
        JsonNode other = create(otherDraft, writer);
        long otherId = other.path("policyVersionId").asLong();
        action(otherId, "ACTIVATE", action(otherId, "APPROVE", action(otherId, "SUBMIT", other, writer, 200), approver, 200), approver, 200);
        String activationKey = key();
        ObjectNode activation = actionRequest("ACTIVATE", approved);
        assertThat(writeError(HttpMethod.POST, "/api/v1/policies/" + copyId + "/actions", activation, approver, activationKey, 422)
                .path("code").asText()).isEqualTo("FGC-POL-002");
        assertThat(statusOf(firstId)).isEqualTo("ACTIVE");
        assertThat(statusOf(otherId)).isEqualTo("ACTIVE");
        assertThat(statusOf(copyId)).isEqualTo("APPROVED");
        assertThat(auditCount(firstId, "POLICY_RETIRE")).isZero();
        assertThat(count("policy_approval_action", firstId)).isEqualTo(3);
        assertThat(auditCount(copyId, "POLICY_ACTIVATE")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.policy_succession WHERE previous_policy_version_id = ?",
                Integer.class, firstId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.policy_write_request WHERE idempotency_key = ?",
                Integer.class, activationKey)).isZero();
    }

    @Test
    void concurrentSuccessorsLeaveOnlyTheNewestVersionActive() throws Exception {
        JsonNode created = create(draft(), writer);
        long id = created.path("policyVersionId").asLong();
        action(id, "ACTIVATE", action(id, "APPROVE", action(id, "SUBMIT", created, writer, 200), approver, 200), approver, 200);
        JsonNode second = write(HttpMethod.POST, "/api/v1/policies/" + id + "/versions",
                cloneRequest("2000-05-01").put("effectiveTo", "2000-12-31"), writer, key(), 201);
        JsonNode third = write(HttpMethod.POST, "/api/v1/policies/" + id + "/versions",
                cloneRequest("2000-09-01").put("effectiveTo", "2000-12-31"), writer, key(), 201);
        long secondId = second.path("policyVersionId").asLong(), thirdId = third.path("policyVersionId").asLong();
        JsonNode approved2 = action(secondId, "APPROVE", action(secondId, "SUBMIT", second, writer, 200), approver, 200);
        JsonNode approved3 = action(thirdId, "APPROVE", action(thirdId, "SUBMIT", third, writer, 200), approver, 200);
        List<MvcResult> outcomes = concurrently(
                () -> perform(HttpMethod.POST, "/api/v1/policies/" + secondId + "/actions", actionRequest("ACTIVATE", approved2), approver, key()),
                () -> perform(HttpMethod.POST, "/api/v1/policies/" + thirdId + "/actions", actionRequest("ACTIVATE", approved3), approver, key()));
        assertThat(outcomes.get(0).getResponse().getStatus()).isIn(200, 409);
        assertThat(outcomes.get(1).getResponse().getStatus()).isEqualTo(200);
        assertThat(statusOf(id)).isEqualTo("RETIRED");
        assertThat(statusOf(thirdId)).isEqualTo("ACTIVE");
        assertThat(statusOf(secondId)).isIn("APPROVED", "RETIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.policy_version WHERE policy_code = ? AND status = 'ACTIVE'",
                Integer.class, created.path("policyCode").asText())).isEqualTo(1);
    }

    @Test
    void rejectsBackdatedCutoverAndRechecksTheBusinessDateAtActivation() throws Exception {
        JsonNode first = create(draft(), writer);
        long firstId = first.path("policyVersionId").asLong();
        action(firstId, "ACTIVATE", action(firstId, "APPROVE", action(firstId, "SUBMIT", first, writer, 200), approver, 200), approver, 200);
        when(policyClock.instant()).thenReturn(Instant.parse("2000-04-01T00:00:00Z"));
        JsonNode backdated = write(HttpMethod.POST, "/api/v1/policies/" + firstId + "/versions",
                cloneRequest("2000-02-01").put("effectiveTo", "2000-12-31"), writer, key(), 201);
        assertThat(actionError(backdated.path("policyVersionId").asLong(), "SUBMIT", backdated, writer, 400)
                .path("field").asText()).isEqualTo("effectiveFrom");
        JsonNode future = write(HttpMethod.POST, "/api/v1/policies/" + firstId + "/versions",
                cloneRequest("2000-07-01").put("effectiveTo", "2000-12-31"), writer, key(), 201);
        long futureId = future.path("policyVersionId").asLong();
        JsonNode approved = action(futureId, "APPROVE", action(futureId, "SUBMIT", future, writer, 200), approver, 200);
        // UTC로는 전날이어도 한국시간으로 적용일이면 이미 늦은 활성화다.
        when(policyClock.instant()).thenReturn(Instant.parse("2000-06-30T15:00:00Z"));
        assertThat(actionError(futureId, "ACTIVATE", approved, approver, 400).path("field").asText()).isEqualTo("effectiveFrom");
        assertThat(statusOf(firstId)).isEqualTo("ACTIVE");
        assertThat(statusOf(futureId)).isEqualTo("APPROVED");
        assertThat(values(detail(futureId, approver).path("allowedActions"))).containsExactly("CLONE");
        assertThat(auditCount(firstId, "POLICY_RETIRE")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.policy_succession WHERE previous_policy_version_id=?",
                Integer.class, firstId)).isZero();
    }

    @Test
    void registeredFourAndSevenYearPoliciesSupplyActualRulesWithoutCostLimitPrerequisite() throws Exception {
        for (boolean seven : List.of(false, true)) {
            String start = seven ? "2029-01-01" : "2027-01-01";
            String contractDate = seven ? "2029-03-02" : "2027-03-02";
            String document = "BD-" + UUID.randomUUID();
            long offering = insertOffering(seven, start, document);
            long contract = insertContract(offering, contractDate);
            ObjectNode documentDraft = installmentDraft(seven, offering, start, document);
            if (seven) {
                ObjectNode regime = (ObjectNode) documentDraft.path("feeRegimeRules").get(0);
                regime.put("longTermMaintenanceFrom", 67).put("longTermMaintenanceTo", 84);
                ObjectNode longTerm = ((ObjectNode) documentDraft.path("commissionRules").get(0)).deepCopy();
                longTerm.put("commissionItemId", insertItem()).put("feeComponentType", "LONG_TERM_MAINTENANCE")
                        .put("installmentFrom", 67).put("calculationType", "FIXED")
                        .putNull("ratePct").put("fixedAmount", "12000.00");
                ((com.fasterxml.jackson.databind.node.ArrayNode) documentDraft.path("commissionRules")).add(longTerm);
            }
            JsonNode created = create(documentDraft, writer);
            long id = created.path("policyVersionId").asLong();
            assertThat(schedulePolicies.resolve(contract, PaymentStage.GA_TO_FC).isSelected()).isFalse();
            JsonNode review = action(id, "SUBMIT", created, writer, 200);
            JsonNode approved = action(id, "APPROVE", review, approver, 200);
            assertThat(schedulePolicies.resolve(contract, PaymentStage.GA_TO_FC).isSelected()).isFalse();
            action(id, "ACTIVATE", approved, approver, 200);

            var selected = schedulePolicies.resolve(contract, PaymentStage.GA_TO_FC);
            assertThat(selected.isSelected()).isTrue();
            assertThat(selected.versionNo()).isEqualTo(1);
            assertThat(selected.sourceRefs()).contains("REG-03", "SRC-022");
            assertThat(selected.selection().limits()).isEmpty();
            assertThat(selected.selection().ruleset().maxInstallments()).isEqualTo(seven ? 84 : 48);
            assertThat(selected.commissionPolicy().getPolicyVersionId()).isEqualTo(id);
            var regular = selected.commissionPolicy().getRules().stream()
                    .filter(r -> r.getFeeComponentType() == FeeComponentType.MAINTENANCE).toList();
            assertThat(regular).hasSize(seven ? 84 : 48);
            assertThat(regular).allSatisfy(r -> {
                assertThat(r.isEqualMonthlyYn()).isTrue();
                assertThat(r.getRatePct()).isEqualByComparingTo(seven ? "24" : "45");
                assertThat(r.getBasisCode()).isEqualTo("MONTHLY_EQUIVALENT_FIRST_PREMIUM");
            });
            assertThat(regular.getLast().getInstallmentTo()).isEqualTo(seven ? 84 : 48);
            var longTerm = selected.commissionPolicy().getRules().stream()
                    .filter(r -> r.getFeeComponentType() == FeeComponentType.LONG_TERM_MAINTENANCE).toList();
            if (seven) {
                assertThat(longTerm).hasSize(18);
                assertThat(longTerm.getFirst().getInstallmentFrom()).isEqualTo(67);
                assertThat(longTerm).allSatisfy(r -> assertThat(r.getFixedAmount()).isEqualByComparingTo("12000"));
            } else {
                assertThat(longTerm).isEmpty();
            }
            assertThat(schedulePolicies.resolve(contract, PaymentStage.INSURER_TO_GA).isSelected()).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.schedule_header WHERE contract_id = ?",
                    Integer.class, contract)).isZero(); // 조회는 생성·확정 부수효과가 없다.
        }
    }

    @Test
    void limitLookupSurvivesCoexistingOfferingSpecificPayoutPolicies() throws Exception {
        String document = "BD-" + UUID.randomUUID();
        long regulatoryId = jdbc.queryForObject("""
                SELECT policy_version_id FROM fgc.policy_version
                WHERE policy_code = 'REG-SEVEN-YEAR-2029-V1' AND status = 'ACTIVE'
                """, Long.class);
        long[] contracts = new long[2];
        long[] policyIds = new long[2];
        for (int i = 0; i < contracts.length; i++) {
            long offering = insertOffering(true, "2029-01-01", document);
            contracts[i] = insertContract(offering, "2029-03-02");
            JsonNode created = create(installmentDraft(true, offering, "2029-01-01", document), writer);
            policyIds[i] = created.path("policyVersionId").asLong();
            JsonNode approved = action(policyIds[i], "APPROVE",
                    action(policyIds[i], "SUBMIT", created, writer, 200), approver, 200);
            action(policyIds[i], "ACTIVATE", approved, approver, 200);
            // 규제 상한 + 지급 정책 하나, 이후 지급 정책 둘이 함께 활성화되어도 모두 조회된다.
            for (int j = 0; j <= i; j++) {
                var limit = limitPolicies.resolve(contracts[j]);
                assertThat(limit.isSelected()).isTrue();
                assertThat(limit.ruleset().policyVersionId()).isEqualTo(regulatoryId);
                assertThat(limit.limit("MAINTENANCE_MONTHLY").orElseThrow().limitPct())
                        .isEqualByComparingTo("0.8");
                var payout = schedulePolicies.resolve(contracts[j], PaymentStage.GA_TO_FC);
                assertThat(payout.isSelected()).isTrue();
                assertThat(payout.commissionPolicy().getPolicyVersionId()).isEqualTo(policyIds[j]);
            }
        }
    }

    @Test
    void rejectsOverlappingLimitRulesetsEvenWhenTheirPayoutOfferingsDiffer() throws Exception {
        String document = "BD-" + UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            long offering = insertOffering(false, "2027-01-01", document);
            JsonNode created = create(installmentDraft(false, offering, "2027-01-01", document), writer);
            long id = created.path("policyVersionId").asLong();
            jdbc.update("""
                    INSERT INTO fgc.fee_regime_ruleset_limit(fee_regime_ruleset_id, limit_code, limit_pct)
                    SELECT fee_regime_ruleset_id, 'UPFRONT_TOTAL', 100
                    FROM fgc.fee_regime_ruleset WHERE policy_version_id = ?
                    """, id);
            JsonNode review = action(id, "SUBMIT", created, writer, 200);
            if (i == 0) {
                action(id, "ACTIVATE", action(id, "APPROVE", review, approver, 200), approver, 200);
                long contract = insertContract(offering, "2027-03-02");
                assertThat(limitPolicies.resolve(contract).resolution().reasonCode())
                        .isEqualTo(FeeRegimeReasonCode.RULESET_LIMIT_MISSING);
            } else {
                actionError(id, "APPROVE", review, approver, 422);
                assertThat(statusOf(id)).isEqualTo("REVIEW");
            }
        }
    }

    @Test
    void filtersPoliciesByActualOfferingEvenWhenDatesAndDocumentsAreIdentical() throws Exception {
        String document = "BD-" + UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            long offering = insertOffering(false, "2027-01-01", document);
            long contract = insertContract(offering, "2027-03-02");
            JsonNode created = create(installmentDraft(false, offering, "2027-01-01", document), writer);
            long id = created.path("policyVersionId").asLong();
            JsonNode approved = action(id, "APPROVE", action(id, "SUBMIT", created, writer, 200), approver, 200);
            action(id, "ACTIVATE", approved, approver, 200);
            var selected = schedulePolicies.resolve(contract, PaymentStage.GA_TO_FC);
            assertThat(selected.isSelected()).isTrue();
            assertThat(selected.commissionPolicy().getPolicyVersionId()).isEqualTo(id);
        }
        // 두 번째 정책이 활성화된 후에도 첫 번째 판매버전 선택은 바뀌지 않는다.
        var rows = jdbc.queryForList("""
                SELECT c.contract_id, c.product_offering_id, cr.policy_version_id
                FROM fgc.insurance_contract c
                JOIN fgc.product_offering po ON po.product_offering_id = c.product_offering_id
                JOIN fgc.commission_rule cr ON cr.product_offering_id = po.product_offering_id
                WHERE po.basic_document_version = ?
                """, document);
        for (var row : rows) {
            assertThat(schedulePolicies.resolve(((Number) row.get("contract_id")).longValue(), PaymentStage.GA_TO_FC)
                    .commissionPolicy().getPolicyVersionId()).isEqualTo(((Number) row.get("policy_version_id")).longValue());
        }
        long sameOffering = ((Number) rows.getFirst().get("product_offering_id")).longValue();
        JsonNode conflict = create(installmentDraft(false, sameOffering, "2027-01-01", document), writer);
        long conflictingId = conflict.path("policyVersionId").asLong();
        JsonNode conflictReview = action(conflictingId, "SUBMIT", conflict, writer, 200);
        actionError(conflictingId, "APPROVE", conflictReview, approver, 422);
        assertThat(statusOf(conflictingId)).isEqualTo("REVIEW");
    }

    @Test
    void submissionChecksOfferingRegimeDocumentDatesPeriodAndInsurer() throws Exception {
        String document = "BD-" + UUID.randomUUID();
        long offering = insertOffering(false, "2027-01-01", document);
        ObjectNode base = installmentDraft(false, offering, "2027-01-01", document);
        List<ObjectNode> invalid = new java.util.ArrayList<>();
        invalid.add(base.deepCopy().put("feeRegimeCode", "SEVEN_YEAR_2029").put("policyType", "SEVEN_YEAR_COMMISSION"));
        ((ObjectNode) invalid.getLast().path("feeRegimeRules").get(0)).put("feeRegimeCode", "SEVEN_YEAR_2029");
        invalid.add(base.deepCopy().put("basicDocumentVersion", document + "-wrong"));
        ((ObjectNode) invalid.getLast().path("feeRegimeRules").get(0)).put("basicDocumentVersion", document + "-wrong");
        invalid.add(base.deepCopy());
        ((ObjectNode) invalid.getLast().path("feeRegimeRules").get(0)).put("salesStartDateFrom", "2027-01-02");
        invalid.add(base.deepCopy());
        ((ObjectNode) invalid.getLast().path("feeRegimeRules").get(0)).put("basicDocumentDateTo", "2026-12-31");
        invalid.add(base.deepCopy().put("effectiveFrom", "2026-01-01").put("effectiveTo", "2026-12-31"));
        invalid.add(base.deepCopy());
        ((ObjectNode) invalid.getLast().path("commissionRules").get(0)).put("insurerId",
                jdbc.queryForObject("SELECT min(insurer_id) FROM fgc.insurer", Long.class));
        for (ObjectNode bad : invalid) {
            bad.put("policyCode", "P014-" + UUID.randomUUID());
            JsonNode created = create(bad, writer);
            long id = created.path("policyVersionId").asLong();
            actionError(id, "SUBMIT", created, writer, 400);
            assertThat(statusOf(id)).isEqualTo("DRAFT");
            assertThat(count("policy_approval_action", id)).isZero();
        }
    }

    @Test
    void validatesInstallmentLimitAgainstOnlyTheRulesetForTheSelectedOffering() throws Exception {
        String document = "BD-" + UUID.randomUUID();
        long offering = insertOffering(false, "2027-07-01", document);
        ObjectNode draft = installmentDraft(false, offering, "2027-01-01", document);
        var regimes = (com.fasterxml.jackson.databind.node.ArrayNode) draft.path("feeRegimeRules");
        ObjectNode later = ((ObjectNode) regimes.get(0)).deepCopy().put("salesStartDateFrom", "2027-07-01");
        ((ObjectNode) regimes.get(0)).put("salesStartDateTo", "2027-06-30").put("maxInstallments", 24);
        regimes.add(later);
        JsonNode created = create(draft, writer);
        action(created.path("policyVersionId").asLong(), "SUBMIT", created, writer, 200);

        long earlier = insertOffering(false, "2027-01-01", document);
        ObjectNode bad = draft.deepCopy().put("policyCode", "P014-" + UUID.randomUUID());
        ((ObjectNode) bad.path("commissionRules").get(0)).put("productOfferingId", earlier);
        JsonNode badCreated = create(bad, writer);
        actionError(badCreated.path("policyVersionId").asLong(), "SUBMIT", badCreated, writer, 400);
    }

    @Test
    void preservesMissingLongTermSettingsAndRoutesFlaggedPoliciesToReview() throws Exception {
        String document = "BD-" + UUID.randomUUID();
        long offering = insertOffering(true, "2029-01-01", document);
        long contract = insertContract(offering, "2029-03-02");
        ObjectNode draft = installmentDraft(true, offering, "2029-01-01", document);
        JsonNode created = create(draft, writer);
        long id = created.path("policyVersionId").asLong();
        JsonNode approved = action(id, "APPROVE", action(id, "SUBMIT", created, writer, 200), approver, 200);
        action(id, "ACTIVATE", approved, approver, 200);
        var selected = schedulePolicies.resolve(contract, PaymentStage.GA_TO_FC);
        assertThat(selected.isSelected()).isTrue();
        assertThat(selected.selection().ruleset().longTermMaintenanceFrom()).isNull();
        assertThat(selected.commissionPolicy().getRules()).allSatisfy(r ->
                assertThat(r.getFeeComponentType()).isEqualTo(FeeComponentType.MAINTENANCE));

        String flaggedDoc = "BD-" + UUID.randomUUID();
        long flaggedOffering = insertOffering(false, "2027-01-01", flaggedDoc);
        long flaggedContract = insertContract(flaggedOffering, "2027-03-02");
        ObjectNode flagged = installmentDraft(false, flaggedOffering, "2027-01-01", flaggedDoc);
        ((ObjectNode) flagged.path("feeRegimeRules").get(0)).put("reviewRequiredYn", true);
        JsonNode flaggedCreated = create(flagged, writer);
        long flaggedId = flaggedCreated.path("policyVersionId").asLong();
        JsonNode flaggedApproved = action(flaggedId, "APPROVE",
                action(flaggedId, "SUBMIT", flaggedCreated, writer, 200), approver, 200);
        action(flaggedId, "ACTIVATE", flaggedApproved, approver, 200);
        var review = schedulePolicies.resolve(flaggedContract, PaymentStage.GA_TO_FC);
        assertThat(review.isSelected()).isFalse();
        assertThat(review.commissionPolicy()).isNull();
        assertThat(review.selection().resolution().reasonCode()).isEqualTo(FeeRegimeReasonCode.RULESET_FLAGGED);
    }

    @Test
    void installmentPolicySuccessionKeepsOldContractRulesAndSelectsNewRulesByContractDate() throws Exception {
        String document = "BD-" + UUID.randomUUID();
        long offering = insertOffering(false, "2027-01-01", document);
        long oldContract = insertContract(offering, "2027-03-02");
        long newContract = insertContract(offering, "2027-10-02");
        JsonNode created = create(installmentDraft(false, offering, "2027-01-01", document), writer);
        long originalId = created.path("policyVersionId").asLong();
        JsonNode approved = action(originalId, "APPROVE",
                action(originalId, "SUBMIT", created, writer, 200), approver, 200);
        action(originalId, "ACTIVATE", approved, approver, 200);
        JsonNode clone = write(HttpMethod.POST, "/api/v1/policies/" + originalId + "/versions",
                cloneRequest("2027-07-01"), writer, key(), 201);
        long nextId = clone.path("policyVersionId").asLong();
        ObjectNode edit = (ObjectNode) detail(nextId, writer).path("editDocument").deepCopy();
        ((ObjectNode) edit.path("commissionRules").get(0)).put("ratePct", "40.000000");
        JsonNode changed = write(HttpMethod.PUT, "/api/v1/policies/" + nextId, edit, writer, key(), 200);
        JsonNode nextApproved = action(nextId, "APPROVE",
                action(nextId, "SUBMIT", changed, writer, 200), approver, 200);
        action(nextId, "ACTIVATE", nextApproved, approver, 200);

        var oldPolicy = schedulePolicies.resolve(oldContract, PaymentStage.GA_TO_FC);
        var newPolicy = schedulePolicies.resolve(newContract, PaymentStage.GA_TO_FC);
        assertThat(oldPolicy.commissionPolicy().getPolicyVersionId()).isEqualTo(originalId);
        assertThat(oldPolicy.versionNo()).isEqualTo(1);
        assertThat(oldPolicy.commissionPolicy().getRules()).allSatisfy(r ->
                assertThat(r.getRatePct()).isEqualByComparingTo("45"));
        assertThat(newPolicy.commissionPolicy().getPolicyVersionId()).isEqualTo(nextId);
        assertThat(newPolicy.versionNo()).isEqualTo(2);
        assertThat(newPolicy.commissionPolicy().getRules()).allSatisfy(r ->
                assertThat(r.getRatePct()).isEqualByComparingTo("40"));
    }

    @Test
    void legacyPolicyWithRulesBeyondItsLimitReturnsReviewInsteadOfUsableCalculationInput() throws Exception {
        String document = "BD-" + UUID.randomUUID();
        long offering = insertOffering(false, "2027-01-01", document);
        long contract = insertContract(offering, "2027-03-02");
        ObjectNode draft = installmentDraft(false, offering, "2027-01-01", document);
        ((ObjectNode) draft.path("feeRegimeRules").get(0)).put("maxInstallments", 24);
        JsonNode created = create(draft, writer);
        long id = created.path("policyVersionId").asLong();
        actionError(id, "SUBMIT", created, writer, 400);
        // 시드/과거 적재로 형식 제약만 충족한 정책이 존재해도 읽기 경로가 자동 계산으로 넘기지 않는다.
        jdbc.update("UPDATE fgc.policy_version SET status='APPROVED', approved_by=?, approved_at=clock_timestamp() WHERE policy_version_id=?",
                approver.getUserId(), id);
        jdbc.update("UPDATE fgc.policy_version SET status='ACTIVE' WHERE policy_version_id=?", id);
        var review = schedulePolicies.resolve(contract, PaymentStage.GA_TO_FC);
        assertThat(review.isSelected()).isFalse();
        assertThat(review.commissionPolicy()).isNull();
        assertThat(review.selection().resolution().reasonCode()).isEqualTo(FeeRegimeReasonCode.COMMISSION_RULE_INVALID);
        assertThat(review.selection().resolution().candidateRulesetIds()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CURRENT", "FOUR_YEAR_2027", "SEVEN_YEAR_2029"})
    void contractRegenerationRejectsInvalidReasonBeforeAnyWrites(String regime) throws Exception {
        boolean seven = regime.equals("SEVEN_YEAR_2029");
        long offering = insertOffering(seven, seven ? "2029-01-01" : "2027-01-01", "REASON-" + UUID.randomUUID());
        jdbc.update("UPDATE fgc.product_offering SET fee_regime_code=? WHERE product_offering_id=?", regime, offering);
        long contract = insertContract(offering, seven ? "2029-03-02" : "2027-03-02");
        long audits = jdbc.queryForObject("SELECT count(*) FROM fgc.audit_log", Long.class);
        String path = "/api/v1/contracts/" + contract + "/schedules/regenerate";
        for (String reason : new String[]{null, "", "   ", "가".repeat(41)}) {
            var legacy = request(HttpMethod.POST, path).with(user(writer)).with(csrf());
            if (reason != null) legacy.param("reason", reason);
            mvc.perform(legacy).andExpect(status().isBadRequest());
            ObjectNode body = mapper.createObjectNode();
            if (reason != null) body.put("reason", reason);
            writeError(HttpMethod.POST, path, body, writer, key(), 400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.schedule_header WHERE contract_id=?", Long.class, contract)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.audit_log", Long.class)).isEqualTo(audits);
    }

    @ParameterizedTest
    @CsvSource({"false, 67", "true, 67", "true, 61"})
    void previewPersistsPolicyInstallmentsAndPreservesVersionsWithIdempotency(boolean seven, int longTermFrom) throws Exception {
        String regime = seven ? "SEVEN_YEAR_2029" : "FOUR_YEAR_2027";
        String start = seven ? "2029-01-01" : "2027-01-01";
        String document = "BD-" + UUID.randomUUID();
        long offering = insertOffering(seven, start, document);
        long contract = insertContract(offering, seven ? "2029-03-02" : "2027-03-02");
        ObjectNode draft = installmentDraft(seven, offering, start, document);
        ((ObjectNode) draft.path("commissionRules").get(0)).put("agentRankCode", "FC");
        ObjectNode second = ((ObjectNode) draft.path("commissionRules").get(0)).deepCopy();
        second.put("commissionItemId", insertItem());
        if (seven) {
            ((ObjectNode) draft.path("feeRegimeRules").get(0)).put("longTermMaintenanceFrom", longTermFrom).put("longTermMaintenanceTo", 84);
            second.put("feeComponentType", "LONG_TERM_MAINTENANCE").put("installmentFrom", longTermFrom)
                    .put("calculationType", "FIXED").putNull("ratePct").put("fixedAmount", "12000");
        }
        ((com.fasterxml.jackson.databind.node.ArrayNode) draft.path("commissionRules")).add(second);
        JsonNode created = create(draft, writer);
        long policy = created.path("policyVersionId").asLong();
        ObjectNode unapproved = mapper.createObjectNode().put("reason", "승인 정책 없음").put("regime", regime)
                .put("policyVersionId", policy).put("expectedSourceVersion", scheduleRead("/api/v1/contracts/" + contract + "/schedules?paymentStage=GA_TO_FC").path("previewContext").path("expectedSourceVersion").asText());
        assertThat(writeError(HttpMethod.POST, "/api/v1/contracts/" + contract + "/schedules/regenerate?paymentStage=GA_TO_FC", unapproved, writer, key(), 422).path("code").asText())
                .isEqualTo("FGC-IMPL-SCHEDULE_REVIEW_REQUIRED");
        JsonNode submitted = action(policy, "SUBMIT", created, writer, 200);
        JsonNode approved = action(policy, "APPROVE", submitted, approver, 200);
        action(policy, "ACTIVATE", approved, approver, 200);
        long operational = jdbc.queryForObject("""
                INSERT INTO fgc.schedule_header(contract_id,payment_stage,policy_version_id,schedule_version_no,schedule_purpose,schedule_regime,status,active_yn)
                SELECT ?, 'GA_TO_FC', policy_version_id, 1, 'OPERATIONAL', 'CURRENT', 'PLANNED', true
                FROM fgc.policy_version WHERE policy_type='CURRENT_COMMISSION' ORDER BY policy_version_id LIMIT 1 RETURNING schedule_header_id
                """, Long.class, contract);
        String contractPath = "/api/v1/contracts/" + contract + "/schedules";
        JsonNode context = scheduleRead(contractPath + "?paymentStage=GA_TO_FC").path("previewContext");
        ObjectNode payload = mapper.createObjectNode().put("reason", "4년 예정 검증").put("regime", regime)
                .put("policyVersionId", policy).put("expectedSourceVersion", context.path("expectedSourceVersion").asText());
        String requestKey = key();
        JsonNode generated = concurrentPreviewWithSameKey(contract, contractPath + "/regenerate?paymentStage=GA_TO_FC", payload, requestKey);
        long header = generated.get(0).asLong();
        writeError(HttpMethod.POST, contractPath + "/regenerate?paymentStage=GA_TO_FC", payload, approver, requestKey, 403);
        assertThat(write(HttpMethod.POST, contractPath + "/regenerate?paymentStage=GA_TO_FC", payload, writer, requestKey, 200)).isEqualTo(generated);
        JsonNode detail = scheduleRead("/api/v1/schedules/" + header);
        assertPreviewPolicyLines(detail, seven, longTermFrom, regime, header, policy);
        assertPreviewVersionLifecycle(contract, header, operational, payload, requestKey, detail);

    }

    private void assertPreviewVersionLifecycle(long contract, long header, long operational,
            ObjectNode payload, String requestKey, JsonNode detail) throws Exception {
        String contractPath = "/api/v1/contracts/" + contract + "/schedules";
        int lineCount = detail.path("lines").size();
        assertThat(writeError(HttpMethod.POST, "/api/v1/schedules/" + header + "/confirm", mapper.createObjectNode(), writer, key(), 409).path("code").asText())
                .isEqualTo("FGC-IMPL-SCHEDULE_PREVIEW_ONLY");
        assertThat(writeError(HttpMethod.POST, contractPath + "/regenerate?paymentStage=GA_TO_FC", payload, writer, key(), 409).path("code").asText())
                .isEqualTo("FGC-IMPL-SCHEDULE_SOURCE_CHANGED");
        payload.put("reason", "다른 요청");
        writeError(HttpMethod.POST, contractPath + "/regenerate?paymentStage=GA_TO_FC", payload, writer, requestKey, 409);
        payload.put("expectedSourceVersion", detail.path("previewContext").path("expectedSourceVersion").asText());
        String nextKey = key();
        JsonNode next = write(HttpMethod.POST, "/api/v1/schedules/" + header + "/regenerate", payload, writer, nextKey, 200);
        assertThat(write(HttpMethod.POST, "/api/v1/schedules/" + header + "/regenerate", payload, writer, nextKey, 200)).isEqualTo(next);
        assertThat(next.path("versionNo").asLong()).isEqualTo(2);
        assertThat(scheduleRead("/api/v1/schedules/" + header).path("header").path("activeYn").asBoolean()).isFalse();
        assertThat(scheduleRead("/api/v1/schedules/" + header).path("lines").size()).isEqualTo(lineCount);
        assertThat(scheduleRead("/api/v1/schedules/" + header + "/versions").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT active_yn FROM fgc.schedule_header WHERE schedule_header_id=?", Boolean.class, operational)).isTrue();
        long current = next.path("scheduleHeaderId").asLong();
        payload.put("expectedSourceVersion", scheduleRead("/api/v1/schedules/" + current).path("previewContext").path("expectedSourceVersion").asText());
        // A freshly read token must not authorize generation from an inactive historical header.
        assertThat(writeError(HttpMethod.POST, "/api/v1/schedules/" + header + "/regenerate", payload, writer, key(), 409).path("code").asText())
                .isEqualTo("FGC-IMPL-SCHEDULE_SOURCE_CHANGED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.schedule_header WHERE contract_id=? AND schedule_purpose='SIMULATION'", Integer.class, contract)).isEqualTo(2);
        assertPreviewRollbackAndConcurrency(contract, current, payload);
    }

    private void assertPreviewRollbackAndConcurrency(long contract, long current, ObjectNode payload) throws Exception {
        // 헤더 INSERT/기존판 비활성화 뒤 계산에 실패해도 전체 트랜잭션이 복구된다.
        jdbc.execute("ALTER TABLE fgc.schedule_header ADD CONSTRAINT fun037_fault_" + contract + " CHECK (contract_id <> " + contract + " OR schedule_version_no < 3)");
        try {
            writeError(HttpMethod.POST, "/api/v1/schedules/" + current + "/regenerate", payload, writer, key(), 500);
        } finally {
            jdbc.execute("ALTER TABLE fgc.schedule_header DROP CONSTRAINT fun037_fault_" + contract);
        }
        assertThat(jdbc.queryForObject("SELECT active_yn FROM fgc.schedule_header WHERE schedule_header_id=?", Boolean.class, current)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.schedule_header WHERE contract_id=? AND schedule_purpose='SIMULATION'", Integer.class, contract)).isEqualTo(2);
        var outcomes = concurrently(
                () -> perform(HttpMethod.POST, "/api/v1/schedules/" + current + "/regenerate", payload, writer, key()),
                () -> perform(HttpMethod.POST, "/api/v1/schedules/" + current + "/regenerate", payload, writer, key()));
        assertThat(outcomes.stream().map(outcome -> outcome.getResponse().getStatus()).sorted().toList()).containsExactly(200, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.schedule_header WHERE contract_id=? AND schedule_purpose='SIMULATION' AND active_yn", Integer.class, contract)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.schedule_header WHERE contract_id=? AND schedule_purpose='SIMULATION'", Integer.class, contract)).isEqualTo(3);
        long newest = jdbc.queryForObject("SELECT schedule_header_id FROM fgc.schedule_header WHERE contract_id=? AND schedule_purpose='SIMULATION' AND active_yn", Long.class, contract);
        payload.put("expectedSourceVersion", scheduleRead("/api/v1/schedules/" + newest).path("previewContext").path("expectedSourceVersion").asText());
        jdbc.update("UPDATE fgc.schedule_header SET status='CANCELLED' WHERE schedule_header_id=?", newest);
        assertThat(writeError(HttpMethod.POST, "/api/v1/schedules/" + newest + "/regenerate", payload, writer, key(), 409).path("code").asText())
                .isEqualTo("FGC-IMPL-SCHEDULE_SOURCE_CHANGED");
    }

    private void assertPreviewPolicyLines(JsonNode detail, boolean seven, int longTermFrom,
            String regime, long header, long policy) throws Exception {
        int installments = seven ? 84 : 48;
        int lineCount = seven ? 84 + (85 - longTermFrom) : 96;
        assertThat(detail.path("header").path("installmentCount").asInt()).isEqualTo(installments);
        assertThat(detail.path("header").path("lineCount").asInt()).isEqualTo(lineCount);
        assertThat(detail.path("header").path("maxInstallmentNo").asInt()).isEqualTo(installments);
        assertThat(detail.path("header").path("schedulePurpose").asText()).isEqualTo("SIMULATION");
        assertThat(detail.path("header").path("canConfirm").asBoolean()).isFalse();
        assertThat(detail.path("lines").size()).isEqualTo(lineCount);
        assertThat(detail.path("header").path("scheduleRegime").asText()).isEqualTo(regime);
        assertThat(detail.path("header").path("expectedTotal").decimalValue()).isEqualByComparingTo(seven ? String.valueOf(2016000 + (85 - longTermFrom) * 12000) : "4320000");
        assertThat(jdbc.queryForObject("SELECT scenario_code FROM fgc.schedule_header WHERE schedule_header_id=?", String.class, header))
                .isEqualTo("PHASE2_" + regime + "_" + policy);
        if (seven) assertLongTermLines(detail, longTermFrom);
        JsonNode listing = scheduleRead("/api/v1/schedules?regime=" + regime + "&page=1&size=100");
        assertThat(StreamSupport.stream(listing.path("content").spliterator(), false)
                .anyMatch(row -> row.path("scheduleHeaderId").asLong() == header && row.path("installmentCount").asInt() == installments)).isTrue();
        JsonNode last = detail.path("lines").get(lineCount - 1);
        assertThat(last.path("installmentNo").asInt()).isEqualTo(installments);
        assertThat(last.path("dueDate").asText()).isEqualTo(seven ? "2036-02-02" : "2031-02-02");
        assertThat(last.path("expectedAmount").decimalValue()).isEqualByComparingTo(seven ? "12000" : "45000");
        assertThat(last.path("feeComponentType").asText()).isEqualTo(seven ? "LONG_TERM_MAINTENANCE" : "MAINTENANCE");
        assertThat(last.path("scheduleLineId").asLong()).isPositive();
    }

    private void assertLongTermLines(JsonNode detail, int longTermFrom) {
        var longTerm = StreamSupport.stream(detail.path("lines").spliterator(), false)
                .filter(line -> line.path("feeComponentType").asText().equals("LONG_TERM_MAINTENANCE")).toList();
        assertThat(longTerm).hasSize(85 - longTermFrom);
        assertThat(longTerm.stream().map(line -> line.path("installmentNo").asInt()).toList())
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(longTermFrom, 84).boxed().toList());
        assertThat(longTerm).allSatisfy(line -> assertThat(line.path("expectedAmount").decimalValue()).isEqualByComparingTo("12000"));
        assertThat(StreamSupport.stream(detail.path("lines").spliterator(), false)
                .filter(line -> line.path("feeComponentType").asText().equals("MAINTENANCE")).toList())
                .allSatisfy(line -> assertThat(line.path("expectedAmount").decimalValue()).isEqualByComparingTo("24000"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void previewStoresOnlyActualFixedPolicyInstallments(boolean seven) throws Exception {
        String document = "BD-" + UUID.randomUUID();
        long offering = insertOffering(seven, seven ? "2029-01-01" : "2027-01-01", document);
        long contract = insertContract(offering, seven ? "2029-03-02" : "2027-03-02");
        ObjectNode draft = installmentDraft(seven, offering, seven ? "2029-01-01" : "2027-01-01", document);
        ((ObjectNode) draft.path("commissionRules").get(0)).put("agentRankCode", "FC")
                .put("installmentTo", 12).put("calculationType", "FIXED").putNull("ratePct").put("fixedAmount", "12000");
        JsonNode created = create(draft, writer);
        long policy = created.path("policyVersionId").asLong();
        JsonNode submitted = action(policy, "SUBMIT", created, writer, 200);
        JsonNode approved = action(policy, "APPROVE", submitted, approver, 200);
        action(policy, "ACTIVATE", approved, approver, 200);
        String path = "/api/v1/contracts/" + contract + "/schedules";
        ObjectNode payload = mapper.createObjectNode().put("reason", "실제 12회차 정액 정책")
                .put("regime", seven ? "SEVEN_YEAR_2029" : "FOUR_YEAR_2027").put("policyVersionId", policy)
                .put("expectedSourceVersion", scheduleRead(path + "?paymentStage=GA_TO_FC").path("previewContext").path("expectedSourceVersion").asText());
        long header = write(HttpMethod.POST, path + "/regenerate?paymentStage=GA_TO_FC", payload, writer, key(), 200).get(0).asLong();
        JsonNode detail = scheduleRead("/api/v1/schedules/" + header);
        assertFixedPolicyLines(detail);
        // 같은 원천·승인 정책이면 자동 생성은 수동 결과를 재사용한다.
        assertThat(scheduleGenerator.generateSchedulesByContractId(contract).scheduleHeaderIds()).isEmpty();
        jdbc.update("UPDATE fgc.insurance_contract SET monthly_equivalent_first_premium=200000 WHERE contract_id=?", contract);
        var automatic = scheduleGenerator.generateSchedulesByContractId(contract);
        assertThat(automatic.scheduleHeaderIds()).hasSize(1);
        assertThat(automatic.createdLineCount()).isEqualTo(12);
        JsonNode autoDetail = scheduleRead("/api/v1/schedules/" + automatic.scheduleHeaderIds().getFirst());
        assertThat(autoDetail.path("header").path("schedulePurpose").asText()).isEqualTo("SIMULATION");
        assertThat(autoDetail.path("header").path("canConfirm").asBoolean()).isFalse();
        for (JsonNode line : autoDetail.path("lines")) {
            assertThat(line.path("basisAmount").decimalValue()).isEqualByComparingTo("200000");
            assertThat(line.path("expectedAmount").decimalValue()).isEqualByComparingTo("12000");
        }
        assertThat(scheduleGenerator.generateSchedulesByContractId(contract).scheduleHeaderIds()).isEmpty();
        jdbc.update("UPDATE fgc.schedule_header SET status='CANCELLED' WHERE schedule_header_id=?", automatic.scheduleHeaderIds().getFirst());
        jdbc.update("UPDATE fgc.insurance_contract SET monthly_equivalent_first_premium=300000 WHERE contract_id=?", contract);
        assertThat(scheduleGenerator.generateSchedulesByContractId(contract).scheduleHeaderIds()).isEmpty();
        assertThat(scheduleRead("/api/v1/schedules/" + header).path("lines").get(0).path("basisAmount").decimalValue())
                .isEqualByComparingTo("100000");
        assertFixedPreviewCsv(header);

    }

    private void assertFixedPolicyLines(JsonNode detail) {
        assertThat(detail.path("header").path("installmentCount").asInt()).isEqualTo(12);
        assertThat(detail.path("header").path("lineCount").asInt()).isEqualTo(12);
        assertThat(detail.path("lines").size()).isEqualTo(12);
        for (JsonNode line : detail.path("lines")) {
            assertThat(line.path("basisCode").asText()).isEqualTo("MONTHLY_EQUIVALENT_FIRST_PREMIUM");
            assertThat(line.path("basisAmount").decimalValue()).isEqualByComparingTo("100000");
            assertThat(line.path("fixedAmount").decimalValue()).isEqualByComparingTo("12000");
            assertThat(line.path("expectedAmount").decimalValue()).isEqualByComparingTo("12000");
        }
    }

    private void assertFixedPreviewCsv(long header) throws Exception {
        String csv = mvc.perform(get("/api/v1/schedules/" + header + "/export.csv").with(user(writer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var csvLines = csv.lines().toList();
        assertThat(csvLines).hasSize(13);
        assertThat(csvLines.getFirst()).endsWith("\"수수료 규칙 ID\",\"정액\"");
        for (String csvLine : csvLines.subList(1, csvLines.size())) {
            String[] cells = java.util.Arrays.stream(csvLine.split(",", -1))
                    .map(cell -> cell.substring(1, cell.length() - 1)).toArray(String[]::new);
            assertThat(cells).hasSize(14);
            assertThat(cells[6]).isEqualTo("MONTHLY_EQUIVALENT_FIRST_PREMIUM");
            assertThat(new java.math.BigDecimal(cells[7])).isEqualByComparingTo("100000");
            assertThat(cells[8]).isEqualTo("FIXED");
            assertThat(cells[9]).isEmpty();
            assertThat(new java.math.BigDecimal(cells[10])).isEqualByComparingTo("12000");
            assertThat(new java.math.BigDecimal(cells[13])).isEqualByComparingTo("12000");
        }    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void monthlyPreviewRollsBackBothStagesWhenSecondStageIsCancelled(boolean seven) throws Exception {
        String document = "BD-" + UUID.randomUUID();
        String from = seven ? "2029-01-01" : "2027-01-01";
        long offering = insertOffering(seven, from, document);
        long contract = insertContract(offering, seven ? "2029-03-02" : "2027-03-02");
        ObjectNode draft = installmentDraft(seven, offering, from, document);
        ObjectNode rule = (ObjectNode) draft.path("commissionRules").get(0);
        rule.put("agentRankCode", "FC");
        ((com.fasterxml.jackson.databind.node.ArrayNode) draft.path("commissionRules"))
                .add(rule.deepCopy().put("paymentStage", "INSURER_TO_GA"));
        JsonNode created = create(draft, writer);
        long policy = created.path("policyVersionId").asLong();
        JsonNode submitted = action(policy, "SUBMIT", created, writer, 200);
        JsonNode approved = action(policy, "APPROVE", submitted, approver, 200);
        action(policy, "ACTIVATE", approved, approver, 200);

        var first = scheduleGenerator.generateSchedulesForValidation(contract);
        assertThat(first.scheduleHeaderIds()).hasSize(2);
        assertThat(first.createdLineCount()).isEqualTo(seven ? 168 : 96);
        assertThat(scheduleGenerator.generateSchedulesForValidation(contract).scheduleHeaderIds()).isEmpty();
        jdbc.update("UPDATE fgc.schedule_header SET status='CANCELLED' WHERE contract_id=? AND payment_stage='GA_TO_FC'", contract);
        jdbc.update("UPDATE fgc.insurance_contract SET monthly_equivalent_first_premium=200000 WHERE contract_id=?", contract);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduleGenerator.generateSchedulesForValidation(contract))
                .isInstanceOf(com.susukkang.fgc.common.exception.FgcBusinessException.class)
                .satisfies(error -> assertThat(((com.susukkang.fgc.common.exception.FgcBusinessException) error).getDetail())
                        .contains("GA_TO_FC", "취소"));
        // INSURER_TO_GA가 먼저 새 판을 만들었더라도 두 번째 단계 실패 시 함께 롤백된다.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fgc.schedule_header WHERE contract_id=?", Long.class, contract))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fgc.schedule_header WHERE contract_id=? AND active_yn", Long.class, contract))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT MAX(basis_amount) FROM fgc.schedule_line WHERE schedule_header_id=?",
                java.math.BigDecimal.class, first.scheduleHeaderIds().getFirst())).isEqualByComparingTo("100000");
    }

    private JsonNode scheduleRead(String path) throws Exception {
        return body(mvc.perform(get(path).with(user(writer))).andExpect(status().isOk()).andReturn()).path("data");
    }

    private ObjectNode installmentDraft(boolean seven, long offering, String from, String document) {
        ObjectNode draft = draft().put("policyType", seven ? "SEVEN_YEAR_COMMISSION" : "FOUR_YEAR_COMMISSION")
                .put("feeRegimeCode", seven ? "SEVEN_YEAR_2029" : "FOUR_YEAR_2027")
                .put("effectiveFrom", from).putNull("effectiveTo").put("basicDocumentVersion", document);
        ((ObjectNode) draft.path("commissionRules").get(0)).put("productOfferingId", offering)
                .put("feeComponentType", "MAINTENANCE").put("installmentTo", seven ? 84 : 48)
                .put("ratePct", seven ? "24.000000" : "45.000000").put("equalMonthlyYn", true);
        ObjectNode regime = draft.putArray("feeRegimeRules").addObject()
                .put("feeRegimeCode", seven ? "SEVEN_YEAR_2029" : "FOUR_YEAR_2027")
                .put("salesStartDateFrom", from).put("basicDocumentVersion", document)
                .put("maxInstallments", seven ? 84 : 48);
        regime.putArray("sourceRefs").add("REG-03").add("SRC-022");
        return draft;
    }

    private long insertOffering(boolean seven, String salesStart, String document) {
        String code = UUID.randomUUID().toString();
        long product = jdbc.queryForObject("""
                INSERT INTO fgc.product(insurer_id, insurer_product_code, standard_product_code, product_name, product_group_code)
                VALUES (?, ?, ?, '분급 정책 연결 검증 상품', 'LIFE') RETURNING product_id
                """, Long.class, insurerId, code, code);
        return jdbc.queryForObject("""
                INSERT INTO fgc.product_offering(product_id, offering_version, sales_start_date, basic_document_version,
                                                basic_document_date, channel_code, fee_regime_code)
                VALUES (?, 'P014', ?::date, ?, ?::date, 'FACE_TO_FACE', ?) RETURNING product_offering_id
                """, Long.class, product, salesStart, document, salesStart, seven ? "SEVEN_YEAR_2029" : "FOUR_YEAR_2027");
    }

    private long insertContract(long offering, String contractDate) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.insurance_contract(insurer_id, product_offering_id, contract_no, contract_date,
                    agent_id, organization_id, premium_per_cycle_amount, first_premium_amount,
                    monthly_equivalent_first_premium, payment_term_months)
                SELECT ?, ?, ?, ?::date, agent_id, organization_id, 100000, 100000, 100000, 240
                FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1 RETURNING contract_id
                """, Long.class, insurerId, offering, "P014-" + UUID.randomUUID(), contractDate);
    }

    private long insertItem() {
        return jdbc.queryForObject("""
                INSERT INTO fgc.commission_item(item_code, item_name, cashflow_type, item_category, effective_from)
                VALUES (?, '장기유지 예정 수수료', 'PAYMENT', 'SALES', DATE '2026-01-01')
                RETURNING commission_item_id
                """, Long.class, "P014-" + UUID.randomUUID());
    }

    private ObjectNode clawbackDraft() {
        ObjectNode draft = draft().put("policyType", "CLAWBACK").putNull("feeRegimeCode");
        draft.putArray("commissionRules");
        draft.putArray("clawbackRules").addObject().put("insurerId", insurerId).put("commissionItemId", itemId)
                .put("elapsedMonthFrom", 0).put("elapsedMonthTo", 11)
                .put("clawbackBasisCode", "PAID_ATTRIBUTED_AMOUNT").put("clawbackRatePct", "50");
        return draft;
    }

    private ObjectNode draft() {
        ObjectNode draft = mapper.createObjectNode();
        draft.put("policyCode", "IT-P647-" + UUID.randomUUID());
        draft.put("policyName", "정책 API 통합 검증");
        draft.put("policyType", "CURRENT_COMMISSION");
        draft.put("sourceClass", "GA_POLICY");
        // V3의 전체 범위 ACTIVE 정책(2026~)을 바꾸지 않고 정상 흐름을 독립적으로 검증한다.
        draft.put("effectiveFrom", "2000-01-01");
        draft.put("effectiveTo", "2000-12-31");
        draft.put("feeRegimeCode", "CURRENT");
        draft.putNull("basicDocumentVersion");
        draft.putArray("regulationRefs").add("REG-03").add("REG-03");
        draft.putArray("sourceRefs").add("SRC-022");
        draft.putArray("sourceDetails").addObject().put("ref", "SRC-022").put("title", "승인된 근거 연결")
                .put("explanation", "정책 등록과 복제에서 근거를 보존한다.").put("url", "https://example.com/policy-evidence");
        ObjectNode rule = draft.putArray("commissionRules").addObject();
        rule.put("paymentStage", "GA_TO_FC");
        rule.put("insurerId", insurerId);
        rule.put("commissionItemId", itemId);
        rule.put("feeComponentType", "CURRENT");
        rule.put("installmentFrom", 1);
        rule.put("installmentTo", 1);
        rule.put("calculationType", "RATE");
        rule.put("basisCode", "MONTHLY_EQUIVALENT_FIRST_PREMIUM");
        rule.put("ratePct", "10.000000");
        rule.putNull("fixedAmount");
        rule.put("equalMonthlyYn", false);
        rule.putNull("paymentConditionCode");
        rule.put("priorityNo", 100);
        draft.putArray("feeRegimeRules");
        draft.putArray("parameters");
        draft.putArray("clawbackRules");
        return draft;
    }

    private ObjectNode cloneRequest(String effectiveFrom) {
        return mapper.createObjectNode().put("effectiveFrom", effectiveFrom).putNull("effectiveTo")
                .put("reason", "승인본을 새 정책 초안으로 복제");
    }

    private ObjectNode actionRequest(String action, JsonNode version) {
        return mapper.createObjectNode().put("actionType", action).put("reason", "정책 통합 검증 " + action)
                .put("expectedStatus", version.path("status").asText())
                .put("expectedUpdatedAt", version.path("updatedAt").asText());
    }

    private JsonNode create(ObjectNode draft, FgcUserDetails actor) throws Exception {
        return write(HttpMethod.POST, "/api/v1/policies", draft, actor, key(), 201);
    }

    private JsonNode action(long id, String action, JsonNode version, FgcUserDetails actor, int expected) throws Exception {
        return write(HttpMethod.POST, "/api/v1/policies/" + id + "/actions",
                actionRequest(action, version), actor, key(), expected);
    }

    private JsonNode actionError(long id, String action, JsonNode version, FgcUserDetails actor, int expected) throws Exception {
        return writeError(HttpMethod.POST, "/api/v1/policies/" + id + "/actions",
                actionRequest(action, version), actor, key(), expected);
    }

    private JsonNode detail(long id, FgcUserDetails actor) throws Exception {
        return body(mvc.perform(get("/api/v1/policies/" + id).with(user(actor)))
                .andExpect(status().isOk()).andReturn()).path("data");
    }

    private JsonNode write(HttpMethod method, String path, JsonNode payload, FgcUserDetails actor,
                           String idempotencyKey, int expected) throws Exception {
        MvcResult result = perform(method, path, payload, actor, idempotencyKey);
        assertThat(result.getResponse().getStatus()).withFailMessage(result.getResponse().getContentAsString())
                .isEqualTo(expected);
        return body(result).path("data");
    }

    private JsonNode writeError(HttpMethod method, String path, JsonNode payload, FgcUserDetails actor,
                                String idempotencyKey, int expected) throws Exception {
        MvcResult result = perform(method, path, payload, actor, idempotencyKey);
        assertThat(result.getResponse().getStatus()).withFailMessage(result.getResponse().getContentAsString())
                .isEqualTo(expected);
        return body(result).path("error");
    }

    private MvcResult perform(HttpMethod method, String path, JsonNode payload,
                               FgcUserDetails actor, String idempotencyKey) throws Exception {
        return mvc.perform(request(method, path).with(user(actor)).with(csrf())
                        .header("Idempotency-Key", idempotencyKey).contentType(APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(payload)))
                .andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private int count(String table, long policyId) {
        return jdbc.queryForObject("SELECT count(*) FROM fgc." + table + " WHERE policy_version_id = ?",
                Integer.class, policyId);
    }

    private int auditCount(long policyId, String action) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM fgc.audit_log
                WHERE entity_type = 'POLICY_VERSION' AND entity_id = ? AND action_code = ?
                """, Integer.class, String.valueOf(policyId), action);
    }

    private long childId(long policyId) {
        return jdbc.queryForObject("SELECT commission_rule_id FROM fgc.commission_rule WHERE policy_version_id = ?",
                Long.class, policyId);
    }

    private String statusOf(long policyId) {
        return jdbc.queryForObject("SELECT status FROM fgc.policy_version WHERE policy_version_id = ?",
                String.class, policyId);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static List<String> values(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    /** Hold the contract row so the first HTTP request keeps its advisory lock while the second arrives. */
    private JsonNode concurrentPreviewWithSameKey(long contract, String path, ObjectNode payload, String requestKey) throws Exception {
        try (var blocker = jdbc.getDataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT contract_id FROM fgc.insurance_contract WHERE contract_id=? FOR UPDATE")) {
                statement.setLong(1, contract);
                statement.executeQuery().close();
            }
            var first = executor.submit(() -> perform(HttpMethod.POST, path, payload, writer, requestKey));
            try {
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10))
                        .pollInterval(Duration.ofMillis(20)).until(() ->
                                Boolean.FALSE.equals(jdbc.queryForObject(
                                        "SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))", Boolean.class,
                                        "schedule-preview:" + requestKey)));
                assertThat(writeError(HttpMethod.POST, path, payload, writer, requestKey, 409).path("code").asText())
                        .isEqualTo("FGC-IMPL-REQUEST_IN_PROGRESS");
            } finally {
                blocker.rollback();
            }
            MvcResult result = first.get(20, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            return mapper.readTree(result.getResponse().getContentAsString()).path("data");
        }
    }

    private static List<MvcResult> concurrently(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(first, second).stream().map(task -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 요청 시작 시간 초과");
                }
                return task.call();
            })).toList();
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(tasks.get(0).get(40, TimeUnit.SECONDS), tasks.get(1).get(40, TimeUnit.SECONDS));
        }
    }
}
