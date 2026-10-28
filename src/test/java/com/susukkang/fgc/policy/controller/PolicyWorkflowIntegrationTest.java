package com.susukkang.fgc.policy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    private FgcUserDetails writer;
    private FgcUserDetails systemWriter;
    private FgcUserDetails approver;
    private long insurerId;
    private long itemId;

    @BeforeEach
    void createIndependentActorsAndRuleScope() {
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
    void rejectsOverlappingActivationWithoutRetiringExistingActiveVersion() throws Exception {
        JsonNode created = create(draft(), writer);
        long id = created.path("policyVersionId").asLong();
        JsonNode review = action(id, "SUBMIT", created, writer, 200);
        JsonNode approved = action(id, "APPROVE", review, approver, 200);
        JsonNode clone = write(HttpMethod.POST, "/api/v1/policies/" + id + "/versions",
                cloneRequest("2000-06-01").put("effectiveTo", "2000-12-31"), writer, key(), 201);
        long cloneId = clone.path("policyVersionId").asLong();
        JsonNode cloneReview = action(cloneId, "SUBMIT", clone, writer, 200);
        JsonNode cloneApproved = action(cloneId, "APPROVE", cloneReview, approver, 200);
        JsonNode active = action(id, "ACTIVATE", approved, approver, 200);

        JsonNode error = actionError(cloneId, "ACTIVATE", cloneApproved, approver, 422);
        assertThat(error.path("code").asText()).isEqualTo("FGC-POL-002");
        assertThat(statusOf(id)).isEqualTo("ACTIVE");
        assertThat(statusOf(cloneId)).isEqualTo("APPROVED");
        assertThat(auditCount(cloneId, "POLICY_ACTIVATE")).isZero();
        assertThat(count("policy_approval_action", cloneId)).isEqualTo(2);
        assertThat(detail(id, writer).path("editDocument").path("expectedUpdatedAt").asText())
                .isEqualTo(active.path("updatedAt").asText());
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
                cloneRequest("2090-01-01"), writer, key(), 201);
        long cloneId = clone.path("policyVersionId").asLong();
        ObjectNode edit = (ObjectNode) detail(cloneId, writer).path("editDocument").deepCopy();
        JsonNode originalFeeRefs = edit.path("feeRegimeRules").get(0).path("sourceRefs").deepCopy();
        long feeId = edit.path("feeRegimeRules").get(0).path("feeRegimeRulesetId").asLong();
        int limitCount = jdbc.queryForObject("SELECT count(*) FROM fgc.fee_regime_ruleset_limit WHERE fee_regime_ruleset_id = ?",
                Integer.class, feeId);
        assertThat(limitCount).isPositive();
        String documentVersion = "P647-LEGACY-" + UUID.randomUUID();
        edit.put("basicDocumentVersion", documentVersion);
        ((ObjectNode) edit.path("feeRegimeRules").get(0)).put("basicDocumentVersion", documentVersion);
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
