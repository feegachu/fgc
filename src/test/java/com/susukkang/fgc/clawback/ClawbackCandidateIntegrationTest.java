package com.susukkang.fgc.clawback;

import com.susukkang.fgc.clawback.dto.ClawbackCandidateSearchCondition;
import com.susukkang.fgc.clawback.service.ClawbackCandidateCaptureService;
import com.susukkang.fgc.clawback.service.ClawbackCandidateQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.susukkang.fgc.clawback.dto.ClawbackCandidateSearchCondition.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** CT-W01 / IF83·84: 실제 일회성 PostgreSQL의 금액·유일성·원천·권한·읽기 전용을 검증한다. */
// 동시성 검증은 커밋된 append-only 원천을 필요로 하므로 다른 도메인의 시드 DB와 분리한다.
@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_clawback_668?currentSchema=fgc")
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "COMPLIANCE")
class ClawbackCandidateIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ClawbackCandidateCaptureService capture;
    @Autowired private ClawbackCandidateQueryService query;
    @Autowired private PlatformTransactionManager transactionManager;
    private long contractId;
    private long agentId;
    private long itemId;
    private String contractNo;

    @BeforeEach
    void independentContract() {
        contractNo = "C668-%_-" + UUID.randomUUID();
        contractId = cloneContract(contractNo);
        agentId = jdbc.queryForObject("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id=?", Long.class, contractId);
        itemId = jdbc.queryForObject("SELECT min(commission_item_id) FROM fgc.commission_item WHERE cashflow_type='PAYMENT'", Long.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LAPSED", "TERMINATED", "CANCELLED"})
    void capturesPolicylessStatusSourcesIdempotentlyWithoutMakingCases(String status) {
        long casesBefore = count("clawback_case");
        long event = event(status, "2026-03-31T15:30:00Z");
        assertThat(capture.captureStatusEvents(List.of(event))).isEqualTo(1);
        long id = candidate(event, "STATUS_EVENT");
        var before = jdbc.queryForObject("SELECT updated_at FROM fgc.clawback_candidate WHERE candidate_id=?", Object.class, id);
        assertThat(capture.captureStatusEvents(List.of(event))).isZero();
        assertThat(candidate(event, "STATUS_EVENT")).isEqualTo(id);
        var detail = query.detail(id);
        assertThat(detail.candidate().eventDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(detail.candidate().calculationStatus()).isEqualTo(CalculationStatus.REVIEW_REQUIRED);
        assertThat(detail.candidate().reviewReasons()).contains("POLICY_MISSING", "CALCULATION_BASIS_UNCONFIRMED");
        assertThat(detail.candidate().paidAmount()).isEqualByComparingTo("0");
        assertThat(detail.candidate().unpaidAmount()).isNull();
        assertThat(detail.candidate().calculatedAmount()).isNull();
        assertThat(detail.candidate().clawbackCaseId()).isNull();
        assertThat(detail.latestCalculation()).isNull();
        assertThat(detail.elapsedMonths()).isNull();
        assertThat(detail.sourceRefs()).singleElement().satisfies(ref -> {
            assertThat(ref.sourceId()).isEqualTo(event);
            assertThat(ref.asOfDate()).isNull();
            assertThat(ref.status()).isEqualTo(status);
        });
        assertThat(jdbc.queryForObject("SELECT updated_at FROM fgc.clawback_candidate WHERE candidate_id=?", Object.class, id)).isEqualTo(before);
        assertThat(count("clawback_case")).isEqualTo(casesBefore);
    }

    @Test
    void readsNeverCreateCandidatesAndIgnoreNonClawbackStatuses() throws Exception {
        long event = event("LAPSED", "2026-04-01T00:00:00+09:00");
        assertThat(query.search(condition(), 1, 20).totalElements()).isZero();
        mvc.perform(get("/api/v1/clawbacks/candidates").param("contractNo", contractNo))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.clawback_candidate WHERE status_event_id=?", Long.class, event)).isZero();
        for (String state : List.of("UNPAID", "ACTIVE", "REVIVED", "MATURED")) {
            assertThat(capture.captureStatusEvents(List.of(event(state, "2026-05-01T00:00:00+09:00")))).isZero();
        }
    }

    @Test
    void arbitrageUsesActualAsOfDateAndPreservesCandidateAfterSourceBecomesClear() {
        long source = arbitrage("GA_TO_FC", "CANDIDATE");
        assertThat(capture.captureArbitrageResult(source)).isEqualTo(1);
        long id = candidate(source, "ARBITRAGE");
        String version = query.detail(id).candidate().sourceVersion();
        assertThat(capture.captureArbitrageResult(source)).isZero();
        var detail = query.detail(id);
        assertThat(detail.candidate().dateBasisLabel()).isEqualTo("검증기준일");
        assertThat(detail.sourceRefs()).singleElement().satisfies(ref -> {
            assertThat(ref.effectiveAt()).isNull();
            assertThat(ref.asOfDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        });
        jdbc.update("UPDATE fgc.arbitrage_check SET result_status='CLEAR', net_difference_amount=0 WHERE arbitrage_check_id=?", source);
        assertThat(capture.captureArbitrageResult(source)).isEqualTo(1);
        assertThat(candidate(source, "ARBITRAGE")).isEqualTo(id);
        assertThat(query.detail(id).candidate().sourceVersion()).isNotEqualTo(version);
        assertThat(query.detail(id).candidate().reviewReasons()).contains("ARBITRAGE_SOURCE_CHANGED");
        assertThat(capture.captureArbitrageResult(arbitrage("INSURER_TO_GA", "CANDIDATE"))).isZero();
        assertThat(capture.captureArbitrageResult(arbitrage("GA_TO_FC", "REVIEW_REQUIRED"))).isZero();
    }

    @Test
    void calculatesOnlyAgreedEvidenceTotalsIncludingHoldAndSeparateRecipients() {
        long header = header("GA_TO_FC", "OPERATIONAL", true, 1);
        line(header, 1, agentId, "100.5", "PLANNED");
        line(header, 2, agentId, "200.5", "HOLD");
        long otherAgent = jdbc.queryForObject("SELECT min(agent_id) FROM fgc.agent WHERE agent_id<>?", Long.class, agentId);
        line(header, 1, otherAgent, "1000", "PLANNED");
        payment(contractId, agentId, 1, "60.5", "PAYMENT", "CONFIRMED");
        payment(contractId, otherAgent, 1, "300", "PAYMENT", "CONFIRMED");
        payment(contractId, agentId, 1, "500", "PAYMENT", "DRAFT");
        payment(contractId, agentId, 1, "20", "DEDUCTION", "CONFIRMED");
        payment(cloneContract("OTHER-" + UUID.randomUUID()), agentId, 1, "900", "PAYMENT", "CONFIRMED");
        long inactive = header("GA_TO_FC", "OPERATIONAL", false, 2);
        line(inactive, 1, agentId, "9000", "PLANNED");
        long insurerIncome = header("INSURER_TO_GA", "OPERATIONAL", true, 1);
        line(insurerIncome, 1, agentId, "8000", "PLANNED");
        long simulation = header("GA_TO_FC", "SIMULATION", true, 1);
        line(simulation, 1, agentId, "7000", "PLANNED");
        long event = event("LAPSED", "2026-04-01T00:00:00+09:00");
        capture.captureStatusEvents(List.of(event));
        var detail = query.detail(candidate(event, "STATUS_EVENT"));
        assertThat(detail.candidate().paidAmount()).isEqualByComparingTo("361");
        assertThat(detail.candidate().unpaidAmount()).isEqualByComparingTo("941");
        assertThat(detail.scheduleLines()).hasSize(3).anySatisfy(row -> {
            assertThat(row.lineStatus()).isEqualTo("HOLD");
            assertThat(row.unpaidAmount()).isEqualByComparingTo("201");
        });
        assertThat(detail.paymentAttributions()).hasSize(4);
    }

    @Test
    void missingPaymentInstallmentPreservesPaidAmountButDoesNotInventOutstandingAmount() {
        long header = header("GA_TO_FC", "OPERATIONAL", true, 1);
        line(header, 1, agentId, "100", "PLANNED");
        payment(contractId, agentId, null, "50", "PAYMENT", "CONFIRMED");
        long source = event("LAPSED", "2026-04-01T00:00:00+09:00");
        capture.captureStatusEvents(List.of(source));
        var detail = query.detail(candidate(source, "STATUS_EVENT"));
        assertThat(detail.candidate().paidAmount()).isEqualByComparingTo("50");
        assertThat(detail.candidate().unpaidAmount()).isNull();
        assertThat(detail.candidate().reviewReasons()).contains("PAYMENT_INSTALLMENT_MISSING");
    }

    @Test
    void contractAttributedPaymentWithoutHeaderInsurerStillReducesTheCorrectInstallment() {
        long header = header("GA_TO_FC", "OPERATIONAL", true, 1);
        line(header, 1, agentId, "100", "HOLD");
        long tx = payment(contractId, agentId, 1, "20", "PAYMENT", "DRAFT");
        jdbc.update("UPDATE fgc.commission_transaction SET insurer_id=NULL, status='CONFIRMED' WHERE commission_transaction_id=?", tx);
        long source = event("LAPSED", "2026-04-01T00:00:00+09:00");
        capture.captureStatusEvents(List.of(source));
        var detail = query.detail(candidate(source, "STATUS_EVENT"));
        assertThat(detail.candidate().paidAmount()).isEqualByComparingTo("20");
        assertThat(detail.candidate().unpaidAmount()).isEqualByComparingTo("80");
    }

    @Test
    void inconsistentPaymentScopeIsReportedInsteadOfSilentlyChangingTotals() {
        long header = header("GA_TO_FC", "OPERATIONAL", true, 1);
        line(header, 1, agentId, "100", "PLANNED");
        long tx = payment(contractId, agentId, 1, "20", "PAYMENT", "DRAFT");
        jdbc.update("""
                UPDATE fgc.commission_transaction SET insurer_id=(SELECT min(insurer_id) FROM fgc.insurer
                  WHERE insurer_id<>(SELECT insurer_id FROM fgc.insurance_contract WHERE contract_id=?)),status='CONFIRMED'
                WHERE commission_transaction_id=?
                """, contractId, tx);
        long source = event("LAPSED", "2026-04-01T00:00:00+09:00");
        capture.captureStatusEvents(List.of(source));
        var detail = query.detail(candidate(source, "STATUS_EVENT"));
        assertThat(detail.candidate().paidAmount()).isNull();
        assertThat(detail.candidate().unpaidAmount()).isNull();
        assertThat(detail.candidate().reviewReasons()).contains("PAYMENT_SCOPE_MISMATCH");
    }

    @Test
    void sourceVersionTracksFinancialChangesAndProcessingEvidenceWithoutWritingOnRead() {
        long source = event("LAPSED", "2026-04-01T00:00:00+09:00");
        capture.captureStatusEvents(List.of(source));
        long id = candidate(source, "STATUS_EVENT");
        String first = query.detail(id).candidate().sourceVersion();
        assertThat(query.detail(id).candidate().sourceVersion()).isEqualTo(first);
        payment(contractId, agentId, 1, "50", "PAYMENT", "CONFIRMED");
        String second = query.detail(id).candidate().sourceVersion();
        assertThat(second).isNotEqualTo(first);
        jdbc.update("""
                INSERT INTO fgc.contract_status_event_processing
                    (contract_status_event_id, processing_job, processing_status, failure_reason)
                VALUES (?, 'TEST-668', 'FAILED', '보류 처리 정책 없음')
                """, source);
        var third = query.detail(id);
        assertThat(third.candidate().sourceVersion()).isNotEqualTo(second);
        assertThat(third.effects()).singleElement().satisfies(effect -> {
            assertThat(effect.processingStatus()).isEqualTo("FAILED");
            assertThat(effect.failureReason()).isEqualTo("보류 처리 정책 없음");
        });
        assertThat(count("clawback_line")).isZero();
    }

    @Test
    void filtersBothDateBasesWithInclusiveBoundsLiteralContractNumberAndStablePaging() {
        long e1 = event("LAPSED", "2026-03-31T15:30:00Z");
        long e2 = event("TERMINATED", "2026-04-02T00:00:00+09:00");
        capture.captureStatusEvents(List.of(e1, e2));
        var condition = condition();
        condition.setDateFrom(LocalDate.of(2026, 4, 1));
        condition.setDateTo(LocalDate.of(2026, 4, 1));
        assertThat(query.search(condition, 1, 1).content()).singleElement()
                .satisfies(row -> assertThat(row.sourceId()).isEqualTo(e1));
        condition.setDateTo(LocalDate.of(2026, 4, 2));
        condition.setSort("eventDate,asc");
        assertThat(query.search(condition, 2, 1).content()).singleElement()
                .satisfies(row -> assertThat(row.sourceId()).isEqualTo(e2));
        assertThat(query.search(condition, 1, 1).totalElements()).isEqualTo(2);
        condition.setDateBasis(DateBasis.CONTRACT_DATE);
        assertThat(query.search(condition, 1, 20).totalElements()).isZero();
        condition.setDateFrom(LocalDate.of(2026, 1, 1));
        condition.setDateTo(LocalDate.of(2026, 1, 1));
        condition.setAgentId(agentId);
        condition.setProductOfferingId(jdbc.queryForObject("SELECT product_offering_id FROM fgc.insurance_contract WHERE contract_id=?", Long.class, contractId));
        condition.setInsurerId(jdbc.queryForObject("SELECT insurer_id FROM fgc.insurance_contract WHERE contract_id=?", Long.class, contractId));
        condition.setTriggerType(TriggerType.STATUS_EVENT);
        condition.setCalculationStatus(CalculationStatus.REVIEW_REQUIRED);
        assertThat(query.search(condition, 1, 20).totalElements()).isEqualTo(2);
        condition.setCalculationStatus(CalculationStatus.CALCULATED);
        assertThat(query.search(condition, 1, 20).totalElements()).isZero();
    }

    @Test
    void apiPreservesNullsAndRejectsInvalidInputAndDirectUnknownIds() throws Exception {
        long source = event("LAPSED", "2026-04-01T00:00:00+09:00");
        capture.captureStatusEvents(List.of(source));
        long id = candidate(source, "STATUS_EVENT");
        mvc.perform(get("/api/v1/clawbacks/candidates/{id}", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidate.candidateId").value(id))
                .andExpect(jsonPath("$.data.candidate.calculatedAmount").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.latestCalculation").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.sourceRefs[0].sourceId").value(source));
        mvc.perform(get("/api/v1/clawbacks/candidates/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("FGC-COMMON-004"));
        for (String[] invalid : List.of(new String[]{"page", "0"}, new String[]{"size", "101"},
                new String[]{"insurerId", "-1"}, new String[]{"agentId", "0"},
                new String[]{"productOfferingId", "0"}, new String[]{"dateBasis", "MADE_UP"},
                new String[]{"dateFrom", "2026-99-99"}, new String[]{"sort", "candidate_id;DROP,asc"},
                new String[]{"calculationStatus", "UNKNOWN"}, new String[]{"triggerType", "UNKNOWN"})) {
            mvc.perform(get("/api/v1/clawbacks/candidates").param(invalid[0], invalid[1]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));
        }
        mvc.perform(get("/api/v1/clawbacks/candidates").param("dateFrom", "2026-05-01").param("dateTo", "2026-04-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("dateTo"));
    }

    @Test
    void anonymousAgentsAndUnrecognizedRolesCannotReadAnyCandidateId() throws Exception {
        for (String path : List.of("/api/v1/clawbacks/candidates", "/api/v1/clawbacks/candidates/1")) {
            mvc.perform(get(path).with(anonymous())).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(user("agent").roles("AGENT"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("unknown").roles("UNKNOWN"))).andExpect(status().isForbidden());
        }
        for (String role : List.of("SETTLEMENT", "GA_ADMIN", "SYSTEM_ADMIN", "COMPLIANCE")) {
            mvc.perform(get("/api/v1/clawbacks/candidates").with(user("internal").roles(role))
                            .param("contractNo", contractNo))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
        }
    }

    @Test
    void sourceAndContractMustMatchEvenForDirectDatabaseWrites() {
        long source = event("LAPSED", "2026-04-01T00:00:00+09:00");
        long other = cloneContract("GUARD-" + UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO fgc.clawback_candidate(contract_id,trigger_type,status_event_id,source_version,event_date)
                VALUES (?, 'STATUS_EVENT', ?, 'fake', DATE '2026-04-01')
                """, other, source)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void findsScopedPoliciesWithoutRevivingRetiredOnesOrInventingAnAppliedPolicy() {
        long policy = policy("ACTIVE");
        long retired = policy("RETIRED");
        long source = event("LAPSED", "2026-04-01T00:00:00+09:00");
        capture.captureStatusEvents(List.of(source));
        long id = candidate(source, "STATUS_EVENT");
        var detail = query.detail(id);
        assertThat(detail.policyCandidates()).extracting(row -> row.policyVersionId()).containsExactly(policy).doesNotContain(retired);
        assertThat(detail.candidate().policyVersionId()).isEqualTo(policy);
        assertThat(detail.candidate().reviewReasons()).doesNotContain("POLICY_MISSING");
        assertThat(detail.candidate().reviewReasons()).contains("CALCULATION_BASIS_UNCONFIRMED");
        long second = policy("ACTIVE");
        detail = query.detail(id);
        assertThat(detail.policyCandidates()).extracting(row -> row.policyVersionId()).containsExactly(policy, second);
        assertThat(detail.candidate().policyVersionId()).isNull();
        assertThat(detail.candidate().reviewReasons()).contains("POLICY_SELECTION_REQUIRED");
    }

    @Test
    void observesCandidatesWithoutModifyingLegacyCaseOrCalculationLines() {
        long policy = policy("ACTIVE");
        long source = event("LAPSED", "2026-04-01T00:00:00+09:00");
        long caseId = jdbc.queryForObject("""
                INSERT INTO fgc.clawback_case(contract_id,trigger_status_event_id,policy_version_id,case_no,status,calculated_amount)
                VALUES (?,?,?,?,'CALCULATED',321) RETURNING clawback_case_id
                """, Long.class, contractId, source, policy, UUID.randomUUID().toString());
        String before = jdbc.queryForObject("SELECT CAST(to_jsonb(c) AS text) FROM fgc.clawback_case c WHERE clawback_case_id=?", String.class, caseId);
        long linesBefore = count("clawback_line");
        capture.captureStatusEvents(List.of(source));
        var detail = query.detail(candidate(source, "STATUS_EVENT"));
        assertThat(detail.candidate().calculatedAmount()).isNull();
        assertThat(detail.latestCalculation()).isNull();
        assertThat(jdbc.queryForObject("SELECT CAST(to_jsonb(c) AS text) FROM fgc.clawback_case c WHERE clawback_case_id=?", String.class, caseId)).isEqualTo(before);
        assertThat(count("clawback_line")).isEqualTo(linesBefore);
    }

    @Test
    @Timeout(30)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentObservationCommitsOneCandidate() throws Exception {
        long source = event("LAPSED", "2026-04-01T00:00:00+09:00");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> task = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return new TransactionTemplate(transactionManager).execute(tx -> capture.captureStatusEvents(List.of(source)));
            };
            var first = workers.submit(task);
            var second = workers.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.clawback_candidate WHERE status_event_id=?", Long.class, source)).isEqualTo(1);
    }

    private ClawbackCandidateSearchCondition condition() {
        var condition = new ClawbackCandidateSearchCondition();
        condition.setContractNo(contractNo);
        return condition;
    }

    private long cloneContract(String number) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.insurance_contract(insurer_id,product_offering_id,contract_no,contract_date,
                    agent_id,organization_id,premium_per_cycle_amount,first_premium_amount,
                    monthly_equivalent_first_premium,payment_term_months)
                SELECT insurer_id,product_offering_id,?,DATE '2026-01-01',agent_id,organization_id,
                       premium_per_cycle_amount,first_premium_amount,monthly_equivalent_first_premium,payment_term_months
                  FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1 RETURNING contract_id
                """, Long.class, number);
    }

    private long event(String state, String instant) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.contract_status_event(contract_id,event_seq,new_status,effective_at,received_at,
                    source_system,source_event_key,source_version)
                SELECT ?,COALESCE(MAX(event_seq),0)+1,?,CAST(? AS timestamptz),now(),'TEST-668',?,'source-v1'
                  FROM fgc.contract_status_event WHERE contract_id=? RETURNING contract_status_event_id
                """, Long.class, contractId, state, instant, UUID.randomUUID().toString(), contractId);
    }

    private long candidate(long source, String type) {
        return jdbc.queryForObject("SELECT candidate_id FROM fgc.clawback_candidate WHERE source_id=? AND trigger_type=?", Long.class, source, type);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM fgc." + table, Long.class);
    }

    private long arbitrage(String stage, String result) {
        long run = jdbc.queryForObject("""
                INSERT INTO fgc.validation_run(validation_month,run_no,run_type,status)
                SELECT DATE '2098-11-01', COALESCE(MAX(run_no),0)+1, 'PRE_CONFIRM', 'CREATED'
                  FROM fgc.validation_run WHERE validation_month=DATE '2098-11-01' RETURNING validation_run_id
                """, Long.class);
        return jdbc.queryForObject("""
                INSERT INTO fgc.arbitrage_check(validation_run_id,contract_id,payment_stage,as_of_date,
                    contract_month_no,cumulative_paid_premium,net_difference_amount,standard_deduction_80_yn,result_status)
                VALUES (?,?,?,DATE '2026-04-01',4,100,10,false,?) RETURNING arbitrage_check_id
                """, Long.class, run, contractId, stage, result);
    }

    private long header(String stage, String purpose, boolean active, int version) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.schedule_header(contract_id,payment_stage,policy_version_id,schedule_version_no,
                    schedule_purpose,scenario_code,schedule_regime,active_yn,generation_reason)
                VALUES (?,?,(SELECT min(policy_version_id) FROM fgc.policy_version),?,?,?,'CURRENT',?,'TEST_668')
                RETURNING schedule_header_id
                """, Long.class, contractId, stage, version, purpose,
                purpose.equals("OPERATIONAL") ? null : "TEST_668", active);
    }

    private long policy(String status) {
        long id = jdbc.queryForObject("""
                INSERT INTO fgc.policy_version(policy_code,policy_name,policy_type,source_class,version_no,
                    effective_from,status,created_by)
                VALUES (?, '환수 후보 정책 범위 검증', 'CLAWBACK','GA_POLICY',1,DATE '2025-01-01','DRAFT',
                    (SELECT min(user_id) FROM fgc.app_user)) RETURNING policy_version_id
                """, Long.class, "C668-" + UUID.randomUUID());
        jdbc.update("""
                INSERT INTO fgc.clawback_rule(policy_version_id,insurer_id,product_offering_id,elapsed_month_from,
                    elapsed_month_to,clawback_basis_code,clawback_rate_pct)
                SELECT ?, insurer_id, product_offering_id, 0, 12, 'UNCONFIRMED_TEST', 50
                  FROM fgc.insurance_contract WHERE contract_id=?
                """, id, contractId);
        jdbc.update("""
                UPDATE fgc.policy_version SET status='APPROVED', approved_at=now(),
                    approved_by=(SELECT max(user_id) FROM fgc.app_user) WHERE policy_version_id=?
                """, id);
        jdbc.update("UPDATE fgc.policy_version SET status=? WHERE policy_version_id=?", status, id);
        return id;
    }

    private void line(long header, int installment, long beneficiary, String amount, String status) {
        jdbc.update("""
                INSERT INTO fgc.schedule_line(schedule_header_id,line_no,installment_no,contract_month_no,due_date,
                    commission_item_id,beneficiary_agent_id,basis_code,basis_amount,calculation_type,fixed_amount,expected_amount,line_status)
                SELECT ?,COALESCE(MAX(line_no),0)+1,?,?,DATE '2026-04-01',?,?,'TEST',100,'FIXED',?,?,?
                  FROM fgc.schedule_line WHERE schedule_header_id=?
                """, header, installment, installment, itemId, beneficiary, new BigDecimal(amount), new BigDecimal(amount), status, header);
    }

    private long payment(long contract, long recipient, Integer installment, String amount, String cashflow, String status) {
        long item = cashflow.equals("PAYMENT") ? itemId : jdbc.queryForObject(
                "SELECT min(commission_item_id) FROM fgc.commission_item WHERE cashflow_type='DEDUCTION'", Long.class);
        long tx = jdbc.queryForObject("""
                INSERT INTO fgc.commission_transaction(payment_stage,source_type,source_business_key,insurer_id,
                    recipient_agent_id,commission_item_id,settlement_month,paid_on,amount,cashflow_type,installment_no)
                SELECT 'GA_TO_FC','GA_MANUAL_PAYMENT',?,insurer_id,?,?,DATE '2026-04-01',DATE '2026-04-01',?,?,?
                  FROM fgc.insurance_contract WHERE contract_id=? RETURNING commission_transaction_id
                """, Long.class, UUID.randomUUID().toString(), recipient, item, new BigDecimal(amount), cashflow, installment, contract);
        jdbc.update("""
                INSERT INTO fgc.transaction_attribution(commission_transaction_id,contract_id,agent_id,
                    attribution_date,attribution_month,attributed_amount,inclusion_status_snapshot,attribution_method)
                VALUES (?,?,?,DATE '2026-04-01',DATE '2026-04-01',?,'INCLUDED','DIRECT')
                """, tx, contract, recipient, new BigDecimal(amount));
        if (!status.equals("DRAFT")) jdbc.update("UPDATE fgc.commission_transaction SET status=? WHERE commission_transaction_id=?", status, tx);
        return tx;
    }
}
