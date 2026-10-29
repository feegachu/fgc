package com.susukkang.fgc.clawback;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.clawback.dto.ClawbackCalculationRequest;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateDetailResponse.CalculationLine;
import com.susukkang.fgc.clawback.service.ClawbackCalculationService;
import com.susukkang.fgc.clawback.service.ClawbackCandidateCaptureService;
import com.susukkang.fgc.clawback.service.ClawbackCandidateQueryService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.susukkang.fgc.clawback.dto.ClawbackCandidateSearchCondition.CalculationStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CT-W01 / IF-API-85 · D-02(운영정책서 제48조의3): 실제 PostgreSQL에서 계산판·멱등·원천 변경·근거 부족을 검증한다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_clawback_669?currentSchema=fgc")
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "SETTLEMENT")
class ClawbackCalculationIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ClawbackCandidateCaptureService capture;
    @Autowired private ClawbackCandidateQueryService query;
    @Autowired private ClawbackCalculationService calculation;
    private long contractId;
    private long agentId;
    private long settlementUser;
    private long baseItem;
    private long incentiveItem;
    private long maintenanceItem;

    @BeforeEach
    void independentContract() {
        contractId = jdbc.queryForObject("""
                INSERT INTO fgc.insurance_contract(insurer_id,product_offering_id,contract_no,contract_date,
                    agent_id,organization_id,premium_per_cycle_amount,first_premium_amount,
                    monthly_equivalent_first_premium,payment_term_months)
                SELECT insurer_id,product_offering_id,?,DATE '2026-01-01',agent_id,organization_id,
                       premium_per_cycle_amount,first_premium_amount,monthly_equivalent_first_premium,payment_term_months
                  FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1 RETURNING contract_id
                """, Long.class, "C669-" + UUID.randomUUID());
        agentId = jdbc.queryForObject("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id=?", Long.class, contractId);
        settlementUser = insertUser("SETTLEMENT").getUserId();
        baseItem = item("BASE_COMMISSION");
        incentiveItem = item("INCENTIVE");
        maintenanceItem = item("MAINTENANCE_COMMISSION");
    }

    @Test
    void roundsEachAttributedLineHalfUpAndKeepsItemsWithoutRulesOutOfTheResult() {
        long policy = policy(new Object[]{baseItem, 0, 11, "100"}, new Object[]{baseItem, 12, 1199, "0"},
                new Object[]{incentiveItem, 0, 11, "33.333333"});
        long base = payment(baseItem, "100.4", "CONFIRMED");
        long incentive = payment(incentiveItem, "200.5", "CONFIRMED");
        long maintenance = payment(maintenanceItem, "50", "CONFIRMED");
        payment(baseItem, "999", "DRAFT");
        long id = lapsedCandidate("2026-04-01T00:00:00+09:00");
        var detail = query.detail(id);
        assertThat(detail.elapsedMonths()).isEqualTo(3);
        assertThat(detail.candidate().calculationStatus()).isEqualTo(CalculationStatus.UNCALCULATED);
        assertThat(detail.candidate().allowedActions()).containsExactly("CALCULATE");

        var result = calculation.calculate(id, request(detail.candidate().sourceVersion(), policy), key(), settlementUser);

        // 100.4→100 ×100%, 200.5→201 ×33.333333% = 66.99…→67. 합계는 행별 반올림 뒤 더한다.
        assertThat(result.lines()).extracting(CalculationLine::transactionAttributionId, CalculationLine::basisAmount,
                        CalculationLine::clawbackAmount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(attribution(base), new BigDecimal("100"), new BigDecimal("100")),
                        org.assertj.core.groups.Tuple.tuple(attribution(incentive), new BigDecimal("201"), new BigDecimal("67")));
        assertThat(result.amount()).isEqualByComparingTo("167");
        assertThat(result.calculationVersion()).isEqualTo(1);
        assertThat(result.lines()).allSatisfy(line -> assertThat(line.elapsedMonths()).isEqualTo(3));
        assertThat(result.basis()).containsEntry("rounding", "LINE_HALF_UP_WON").containsEntry("elapsedMonths", 3);
        assertThat((List<?>) result.basis().get("excluded")).singleElement()
                .satisfies(row -> assertThat(((Number) ((Map<?, ?>) row).get("transactionAttributionId")).longValue())
                        .isEqualTo(attribution(maintenance)));
        assertThat(jdbc.queryForObject("SELECT status FROM fgc.clawback_case WHERE candidate_id=?", String.class, id))
                .isEqualTo("CALCULATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.clawback_line WHERE clawback_case_id=?", Long.class,
                result.clawbackCaseId())).isEqualTo(2);

        detail = query.detail(id);
        assertThat(detail.candidate().calculationStatus()).isEqualTo(CalculationStatus.CALCULATED);
        assertThat(detail.candidate().calculatedAmount()).isEqualByComparingTo("167");
        assertThat(detail.candidate().clawbackCaseId()).isEqualTo(result.clawbackCaseId());
        assertThat(detail.latestCalculation().calculationVersion()).isEqualTo(1);
        assertThat(detail.latestCalculation().amount()).isEqualByComparingTo("167");
    }

    @Test
    void sameKeyReplaysAndSameInputConvergesButAReusedKeyWithAnotherBodyConflicts() {
        long policy = policy(new Object[]{baseItem, 0, 1199, "100"});
        payment(baseItem, "1000", "CONFIRMED");
        long id = lapsedCandidate("2026-04-01T00:00:00+09:00");
        String version = query.detail(id).candidate().sourceVersion();
        String key = key();
        var first = calculation.calculate(id, request(version, policy), key, settlementUser);
        assertThat(calculation.calculate(id, request(version, policy), key, settlementUser)).isEqualTo(first);
        assertThat(calculation.calculate(id, request(version, policy), key(), settlementUser)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.clawback_calculation WHERE candidate_id=?", Long.class, id))
                .isEqualTo(1);
        var otherReason = new ClawbackCalculationRequest(version, policy, "다른 사유");
        assertThatThrownBy(() -> calculation.calculate(id, otherReason, key, settlementUser))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_IDEMPOTENCY_CONFLICT));
    }

    @Test
    void sourceChangeMarksTheLatestPlateStaleRejectsOldVersionAndAppendsANewPlate() {
        long policy = policy(new Object[]{baseItem, 0, 1199, "100"});
        payment(baseItem, "1000", "CONFIRMED");
        long id = lapsedCandidate("2026-04-01T00:00:00+09:00");
        String oldVersion = query.detail(id).candidate().sourceVersion();
        var first = calculation.calculate(id, request(oldVersion, policy), key(), settlementUser);
        String firstSnapshot = snapshot(first.clawbackCaseId(), 1);

        payment(baseItem, "500", "CONFIRMED");
        var stale = query.detail(id);
        assertThat(stale.candidate().sourceVersion()).isNotEqualTo(oldVersion);
        assertThat(stale.candidate().calculationStatus()).isEqualTo(CalculationStatus.REVIEW_REQUIRED);
        assertThat(stale.candidate().reviewReasons()).contains("CALCULATION_STALE");
        assertThat(stale.candidate().calculatedAmount()).isNull();
        assertThat(stale.latestCalculation().calculationVersion()).isEqualTo(1);
        assertThatThrownBy(() -> calculation.calculate(id, request(oldVersion, policy), key(), settlementUser))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_STATE_CHANGED));

        var second = calculation.calculate(id, request(stale.candidate().sourceVersion(), policy), key(), settlementUser);
        assertThat(second.calculationVersion()).isEqualTo(2);
        assertThat(second.amount()).isEqualByComparingTo("1500");
        assertThat(snapshot(first.clawbackCaseId(), 1)).isEqualTo(firstSnapshot);
        assertThat(query.detail(id).candidate().calculatedAmount()).isEqualByComparingTo("1500");
    }

    @Test
    void zeroRateBandIsARealCalculatedZeroWon() {
        jdbc.update("UPDATE fgc.insurance_contract SET contract_date=DATE '2023-01-01' WHERE contract_id=?", contractId);
        long policy = policy(new Object[]{baseItem, 0, 23, "100"}, new Object[]{baseItem, 24, 1199, "0"});
        payment(baseItem, "1000", "CONFIRMED");
        long id = lapsedCandidate("2026-04-01T00:00:00+09:00");
        var detail = query.detail(id);
        assertThat(detail.elapsedMonths()).isEqualTo(39);
        var result = calculation.calculate(id, request(detail.candidate().sourceVersion(), policy), key(), settlementUser);
        assertThat(result.amount()).isEqualByComparingTo("0");
        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.appliedRatePct()).isEqualByComparingTo("0");
            assertThat(line.clawbackAmount()).isEqualByComparingTo("0");
        });
        detail = query.detail(id);
        assertThat(detail.candidate().calculationStatus()).isEqualTo(CalculationStatus.CALCULATED);
        assertThat(detail.candidate().calculatedAmount()).isEqualByComparingTo("0");
    }

    @Test
    void insufficientBasisIsRejectedWithoutCreatingACaseOrPlate() {
        long narrow = policy(new Object[]{baseItem, 0, 1, "100"});
        long id = lapsedCandidate("2026-04-01T00:00:00+09:00");
        String version = query.detail(id).candidate().sourceVersion();
        assertMissing(() -> calculation.calculate(id, request(version, narrow), key(), settlementUser), "paymentAttributions");
        payment(baseItem, "1000", "CONFIRMED");
        String withPayment = query.detail(id).candidate().sourceVersion();
        assertMissing(() -> calculation.calculate(id, request(withPayment, narrow), key(), settlementUser), "clawbackRule");
        long notOffered = jdbc.queryForObject("SELECT min(policy_version_id) FROM fgc.policy_version WHERE policy_type <> 'CLAWBACK'", Long.class);
        assertMissing(() -> calculation.calculate(id, request(withPayment, notOffered), key(), settlementUser), "policyVersionId");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.clawback_case WHERE candidate_id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.clawback_calculation WHERE candidate_id=?", Long.class, id)).isZero();
    }

    @Test
    void demoVirtualPolicyAppliesTheFirstYearBandToBaseCommission() {
        long virtual = jdbc.queryForObject("SELECT policy_version_id FROM fgc.policy_version WHERE policy_code='CLAW-GA-VIRTUAL-2026'", Long.class);
        payment(baseItem, "1000", "CONFIRMED");
        long id = lapsedCandidate("2026-04-01T00:00:00+09:00");
        var detail = query.detail(id);
        assertThat(detail.policyCandidates()).extracting(p -> p.policyVersionId()).contains(virtual);
        var result = calculation.calculate(id, request(detail.candidate().sourceVersion(), virtual), key(), settlementUser);
        assertThat(result.amount()).isEqualByComparingTo("1000");
        assertThat(result.lines()).singleElement().satisfies(line -> assertThat(line.appliedRatePct()).isEqualByComparingTo("100"));
    }

    @Test
    void apiRequiresProcessRoleAndKeyAndReportsBlockedCandidatesAs422() throws Exception {
        long policy = policy(new Object[]{baseItem, 0, 1199, "100"});
        payment(baseItem, "1000", "CONFIRMED");
        long id = lapsedCandidate("2026-04-01T00:00:00+09:00");
        String body = "{\"expectedSourceVersion\":\"" + query.detail(id).candidate().sourceVersion()
                + "\",\"policyVersionId\":" + policy + ",\"reason\":\"정책 확인 후 계산\"}";
        mvc.perform(post("/api/v1/clawbacks/candidates/{id}/calculations", id).with(user(insertUser("COMPLIANCE"))).with(csrf())
                        .header("Idempotency-Key", key()).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        FgcUserDetails settlement = insertUser("SETTLEMENT");
        mvc.perform(post("/api/v1/clawbacks/candidates/{id}/calculations", id).with(user(settlement)).with(csrf())
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("Idempotency-Key"));
        mvc.perform(post("/api/v1/clawbacks/candidates/{id}/calculations", id).with(user(settlement)).with(csrf())
                        .header("Idempotency-Key", key()).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.amount").value(1000))
                .andExpect(jsonPath("$.data.calculationVersion").value(1));

        // 후속 사건으로 대체된 후보는 근거 부족으로 막는다.
        event("TERMINATED", "2026-04-15T00:00:00+09:00");
        mvc.perform(post("/api/v1/clawbacks/candidates/{id}/calculations", id).with(user(settlement)).with(csrf())
                        .header("Idempotency-Key", key()).contentType(APPLICATION_JSON)
                        .content(body.replaceAll("\"expectedSourceVersion\":\"[^\"]+\"",
                                "\"expectedSourceVersion\":\"" + query.detail(id).candidate().sourceVersion() + "\"")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FGC-IMPL-POLICY_MISSING"));
    }

    private void assertMissing(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(FgcBusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_POLICY_MISSING);
            assertThat(e.getField()).isEqualTo(field);
        });
    }

    private static ClawbackCalculationRequest request(String sourceVersion, long policy) {
        return new ClawbackCalculationRequest(sourceVersion, policy, "정책 확인 후 계산");
    }

    private static String key() {
        return "claw-" + UUID.randomUUID();
    }

    private long lapsedCandidate(String instant) {
        long event = event("LAPSED", instant);
        capture.captureStatusEvents(List.of(event));
        return jdbc.queryForObject("SELECT candidate_id FROM fgc.clawback_candidate WHERE status_event_id=?", Long.class, event);
    }

    private long event(String state, String instant) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.contract_status_event(contract_id,event_seq,new_status,effective_at,received_at,
                    source_system,source_event_key,source_version)
                SELECT ?,COALESCE(MAX(event_seq),0)+1,?,CAST(? AS timestamptz),now(),'TEST-669',?,'source-v1'
                  FROM fgc.contract_status_event WHERE contract_id=? RETURNING contract_status_event_id
                """, Long.class, contractId, state, instant, UUID.randomUUID().toString(), contractId);
    }

    private long item(String code) {
        return jdbc.queryForObject("SELECT commission_item_id FROM fgc.commission_item WHERE item_code=?", Long.class, code);
    }

    /** 보험회사·판매버전을 비운 ACTIVE 환수 정책. rule = {수수료 항목, 경과월 from, to, 환수율}. */
    private long policy(Object[]... rules) {
        long id = jdbc.queryForObject("""
                INSERT INTO fgc.policy_version(policy_code,policy_name,policy_type,source_class,version_no,
                    effective_from,status,created_by)
                VALUES (?, '환수 계산 검증 정책', 'CLAWBACK','GA_POLICY',1,DATE '2020-01-01','DRAFT',
                    (SELECT min(user_id) FROM fgc.app_user)) RETURNING policy_version_id
                """, Long.class, "C669-" + UUID.randomUUID());
        for (Object[] rule : rules) {
            jdbc.update("""
                    INSERT INTO fgc.clawback_rule(policy_version_id,commission_item_id,elapsed_month_from,
                        elapsed_month_to,clawback_basis_code,clawback_rate_pct)
                    VALUES (?,?,?,?,'PAID_ATTRIBUTED_AMOUNT',?)
                    """, id, rule[0], rule[1], rule[2], new BigDecimal((String) rule[3]));
        }
        jdbc.update("""
                UPDATE fgc.policy_version SET status='APPROVED', approved_at=now(),
                    approved_by=(SELECT max(user_id) FROM fgc.app_user) WHERE policy_version_id=?
                """, id);
        jdbc.update("UPDATE fgc.policy_version SET status='ACTIVE' WHERE policy_version_id=?", id);
        return id;
    }

    private long payment(long item, String amount, String status) {
        long tx = jdbc.queryForObject("""
                INSERT INTO fgc.commission_transaction(payment_stage,source_type,source_business_key,insurer_id,
                    recipient_agent_id,commission_item_id,settlement_month,paid_on,amount,cashflow_type,installment_no)
                SELECT 'GA_TO_FC','GA_MANUAL_PAYMENT',?,insurer_id,?,?,DATE '2026-03-01',DATE '2026-03-01',?,'PAYMENT',1
                  FROM fgc.insurance_contract WHERE contract_id=? RETURNING commission_transaction_id
                """, Long.class, UUID.randomUUID().toString(), agentId, item, new BigDecimal(amount), contractId);
        jdbc.update("""
                INSERT INTO fgc.transaction_attribution(commission_transaction_id,contract_id,agent_id,
                    attribution_date,attribution_month,attributed_amount,inclusion_status_snapshot,attribution_method)
                VALUES (?,?,?,DATE '2026-03-01',DATE '2026-03-01',?,'INCLUDED','DIRECT')
                """, tx, contractId, agentId, new BigDecimal(amount));
        if (!status.equals("DRAFT")) jdbc.update("UPDATE fgc.commission_transaction SET status=? WHERE commission_transaction_id=?", status, tx);
        return tx;
    }

    private long attribution(long transaction) {
        return jdbc.queryForObject("SELECT transaction_attribution_id FROM fgc.transaction_attribution WHERE commission_transaction_id=?",
                Long.class, transaction);
    }

    private String snapshot(long caseId, int version) {
        return jdbc.queryForObject("SELECT result_snapshot::text FROM fgc.clawback_calculation WHERE clawback_case_id=? AND version_no=?",
                String.class, caseId, version);
    }

    private FgcUserDetails insertUser(String role) {
        String login = "c669-" + UUID.randomUUID();
        Long id = jdbc.queryForObject("""
                INSERT INTO fgc.app_user(login_id, password_hash, user_name, role_id)
                VALUES (?, 'unused', '환수 계산 검증 사용자', (SELECT role_id FROM fgc.app_role WHERE role_code = ?))
                RETURNING user_id
                """, Long.class, login, role);
        return new FgcUserDetails(new AppUserView(id, login, "unused", "환수 계산 검증 사용자", role, "ACTIVE"), true, true);
    }
}
