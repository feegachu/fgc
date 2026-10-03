package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.dto.AgentCapMonitoringRow;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunResultSummaryRow;
import com.susukkang.fgc.validation.dto.ValidationTargetListRow;
import com.susukkang.fgc.validation.mapper.ValidationRunDetailMapper;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import com.susukkang.fgc.validation.repository.ValidationTargetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #379 Phase 8 — ValidationRunDetailMapper(MyBatis, 여전히 존재)와 새 Repository
 * 메서드(JPQL/네이티브)가 같은 데이터에 대해 정확히 같은 결과를 내는지 직접 비교한다.
 * 특히 summarize()는 여러 테이블(일부는 아직 JPA 엔티티가 없는 arbitrage_check 등)을
 * 넘나드는 네이티브 SQL이라, mock으로는 집계·조인 로직이 원본과 동일한지 증명할 수 없다.
 */
@SpringBootTest
@Transactional
class ValidationRunDetailQueriesIntegrationTest {

    @Autowired
    private ValidationRunDetailMapper mapper;
    @Autowired
    private ValidationRunRepository validationRunRepository;
    @Autowired
    private ValidationTargetRepository validationTargetRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long contractId(String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, contractNo);
    }

    private Long productOfferingId(Long contractId) {
        return jdbcTemplate.queryForObject(
                "SELECT product_offering_id FROM fgc.insurance_contract WHERE contract_id = ?",
                Long.class, contractId);
    }

    private Long capRuleSetId(String paymentStage) {
        return jdbcTemplate.queryForObject(
                "SELECT MIN(cap_rule_set_id) FROM fgc.cap_rule_set WHERE payment_stage = ?",
                Long.class, paymentStage);
    }

    private Long insertValidationRun(LocalDate month, int runNo) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                VALUES (?, ?, 'MONTHLY', 'CREATED')
                RETURNING validation_run_id
                """, Long.class, month, runNo);
        jdbcTemplate.update("UPDATE fgc.validation_run SET status='RUNNING' WHERE validation_run_id=?", id);
        return id;
    }

    private void insertTarget(Long runId, Long contractId, String selectionStatus, String reason) {
        jdbcTemplate.update("""
                INSERT INTO fgc.validation_target
                    (validation_run_id, contract_id, product_offering_id, selection_status, selection_reason)
                VALUES (?, ?, ?, ?, ?)
                """, runId, contractId, productOfferingId(contractId), selectionStatus, reason);
    }

    private void insertCapCheck(Long runId, Long contractId, String paymentStage, String resultStatus) {
        jdbcTemplate.update("""
                INSERT INTO fgc.cap_check
                    (validation_run_id, contract_id, payment_stage, cap_rule_set_id, check_kind, as_of_date,
                     base_premium_amount, refund_12m_amount, compliance_deduction_amount,
                     limit_amount, included_amount, remaining_amount, usage_pct, result_status, checked_at)
                VALUES (?, ?, ?, ?, 'MONTHLY', '2026-07-01', 100000, 0, 0, 1200000, 0, 1200000, 0, ?, now())
                """, runId, contractId, paymentStage, capRuleSetId(paymentStage), resultStatus);
    }

    private void insertArbitrageCheck(Long runId, Long contractId, String resultStatus) {
        jdbcTemplate.update("""
                INSERT INTO fgc.arbitrage_check
                    (validation_run_id, contract_id, as_of_date, contract_month_no,
                     cumulative_paid_premium, net_difference_amount, standard_deduction_80_yn, result_status)
                VALUES (?, ?, '2026-07-01', 1, 100000, 0, false, ?)
                """, runId, contractId, resultStatus);
    }

    private Long journalAccountId() {
        return jdbcTemplate.queryForObject(
                "SELECT MIN(journal_account_id) FROM fgc.journal_account", Long.class);
    }

    private void insertJournal(Long runId, String journalNo, boolean balanced) {
        Long headerId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id, validation_run_id)
                VALUES (?, CURRENT_DATE, 'ADJUSTMENT', 'TEST', ?, ?)
                RETURNING journal_header_id
                """, Long.class, journalNo, journalNo, runId);
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line (journal_header_id, line_no, journal_account_id, debit_amount)
                VALUES (?, 1, ?, 1000)
                """, headerId, journalAccountId());
        if (balanced) {
            jdbcTemplate.update("""
                    INSERT INTO fgc.journal_line (journal_header_id, line_no, journal_account_id, credit_amount)
                    VALUES (?, 2, ?, 1000)
                    """, headerId, journalAccountId());
        }
    }

    private Long insertReconciliationRun(Long runId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_run (settlement_month, payment_stage, validation_run_id)
                VALUES ('2026-07-01', 'INSURER_TO_GA', ?)
                RETURNING reconciliation_run_id
                """, Long.class, runId);
    }

    private void insertReconciliationResult(Long reconRunId, String matchGroupKey, String resultType,
                                             BigDecimal differenceAmount) {
        jdbcTemplate.update("""
                INSERT INTO fgc.reconciliation_result
                    (reconciliation_run_id, match_group_key, result_type, difference_amount)
                VALUES (?, ?, ?, ?)
                """, reconRunId, matchGroupKey, resultType, differenceAmount);
    }

    private Long anyPolicyVersionId() {
        return jdbcTemplate.queryForObject(
                "SELECT MIN(policy_version_id) FROM fgc.policy_version WHERE policy_type = 'CURRENT_COMMISSION'",
                Long.class);
    }

    private void insertScheduleHeader(Long runId, Long contractId, String paymentStage) {
        Integer nextVersion = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(schedule_version_no), 0) + 1
                  FROM fgc.schedule_header
                 WHERE contract_id = ? AND payment_stage = ? AND schedule_purpose = 'OPERATIONAL'
                """, Integer.class, contractId, paymentStage);
        jdbcTemplate.update("""
                INSERT INTO fgc.schedule_header
                    (contract_id, payment_stage, policy_version_id, schedule_version_no,
                     schedule_regime, active_yn, validation_run_id)
                VALUES (?, ?, ?, ?, 'CURRENT', false, ?)
                """, contractId, paymentStage, anyPolicyVersionId(), nextVersion, runId);
    }

    @Test
    void findHeaderByIdMatchesLegacyMapper() {
        Long runId = insertValidationRun(LocalDate.of(2032, 1, 1), 1);

        ValidationRunListRow legacy = mapper.findHeaderById(runId);
        ValidationRunListRow fromRepository = validationRunRepository.findHeaderById(runId).orElseThrow();

        assertThat(fromRepository).usingRecursiveComparison().isEqualTo(legacy);
    }

    @Test
    void findTargetsMatchesLegacyMapperOrderingAndFields() {
        Long runId = insertValidationRun(LocalDate.of(2032, 2, 1), 1);
        Long selected = contractId("FGC-FGL01-202607-0001");
        Long excluded = contractId("FGC-FGL01-202607-0002");
        Long review = contractId("FGC-FGL01-202607-0004");
        insertTarget(runId, selected, "SELECTED", null);
        insertTarget(runId, excluded, "EXCLUDED", "취소 계약");
        insertTarget(runId, review, "REVIEW_REQUIRED", "환급률표 누락");

        List<ValidationTargetListRow> legacy = mapper.findTargets(runId, 200);
        List<ValidationTargetListRow> fromRepository =
                validationTargetRepository.findTargets(runId, PageRequest.of(0, 200));

        assertThat(fromRepository).hasSize(3);
        assertThat(fromRepository).usingRecursiveFieldByFieldElementComparator().containsExactlyElementsOf(legacy);
    }

    @Test
    void summarizeMatchesLegacyMapperAcrossEveryBlock() {
        Long runId = insertValidationRun(LocalDate.of(2032, 3, 1), 1);
        Long otherRunId = insertValidationRun(LocalDate.of(2032, 4, 1), 1);
        Long c1 = contractId("FGC-FGL01-202607-0001");
        Long c2 = contractId("FGC-FGL01-202607-0002");

        insertTarget(runId, c1, "SELECTED", null);
        insertTarget(runId, c2, "REVIEW_REQUIRED", "검토");
        insertCapCheck(runId, c1, "INSURER_TO_GA", "VIOLATION");
        insertCapCheck(runId, c2, "INSURER_TO_GA", "WARNING");
        insertArbitrageCheck(runId, c1, "CANDIDATE");
        insertJournal(runId, "VRUN-CMP-JRN-1", true);
        insertJournal(runId, "VRUN-CMP-JRN-2", false);
        Long reconRunId = insertReconciliationRun(runId);
        insertReconciliationResult(reconRunId, "VRUN-CMP-REC-1", "MATCHED", BigDecimal.ZERO);
        insertReconciliationResult(reconRunId, "VRUN-CMP-REC-2", "AMOUNT_DIFFERENCE", new BigDecimal("15000"));
        insertReconciliationResult(reconRunId, "VRUN-CMP-REC-3", "EXPECTED_MISSING", new BigDecimal("30000"));
        insertScheduleHeader(runId, c1, "INSURER_TO_GA");
        insertScheduleHeader(runId, c1, "GA_TO_FC");

        // 다른 실행의 결과는 집계에 섞이면 안 된다 — legacy/new 둘 다 이 오염을 막아야 한다.
        insertTarget(otherRunId, c1, "SELECTED", null);
        insertCapCheck(otherRunId, c1, "INSURER_TO_GA", "VIOLATION");
        insertJournal(otherRunId, "VRUN-CMP-JRN-9", false);

        ValidationRunResultSummaryRow legacy = mapper.summarize(runId);
        ValidationRunResultSummaryRow fromRepository = validationRunRepository.summarize(runId).orElseThrow();

        assertThat(fromRepository).usingRecursiveComparison().isEqualTo(legacy);
        // 핵심 분기 하나는 숫자로도 직접 고정 — 회귀 시 recursiveComparison만으로는
        // "둘 다 틀렸지만 같이 틀렸다"를 놓칠 수 있다.
        assertThat(fromRepository.getCapViolationCount()).isEqualTo(1);
        assertThat(fromRepository.getJournalImbalanceCount()).isEqualTo(1);
        assertThat(fromRepository.getReconciliationMismatchCount()).isEqualTo(2);
        assertThat(fromRepository.getScheduleGeneratedCount()).isEqualTo(2);
        assertThat(fromRepository.getReconciliationDifferenceAmountTotal()).isEqualByComparingTo("45000");
    }

    @Test
    void summarizeMatchesLegacyMapperForRunWithoutAnyResults() {
        Long runId = insertValidationRun(LocalDate.of(2032, 5, 1), 1);

        ValidationRunResultSummaryRow legacy = mapper.summarize(runId);
        ValidationRunResultSummaryRow fromRepository = validationRunRepository.summarize(runId).orElseThrow();

        assertThat(fromRepository).usingRecursiveComparison().isEqualTo(legacy);
    }

    @Test
    void summarizeCapByAgentMatchesLegacyMapper() {
        Long runId = insertValidationRun(LocalDate.of(2032, 6, 1), 1);
        Long c1 = contractId("FGC-FGL01-202607-0001");

        insertCapCheck(runId, c1, "INSURER_TO_GA", "VIOLATION");
        insertCapCheck(runId, c1, "GA_TO_FC", "NORMAL");

        List<AgentCapMonitoringRow> legacy = mapper.summarizeCapByAgent(runId);
        List<AgentCapMonitoringRow> fromRepository = validationRunRepository.summarizeCapByAgent(runId);

        assertThat(fromRepository).usingRecursiveFieldByFieldElementComparator().containsExactlyElementsOf(legacy);
    }
}
