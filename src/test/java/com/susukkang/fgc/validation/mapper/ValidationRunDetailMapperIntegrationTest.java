package com.susukkang.fgc.validation.mapper;

import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunResultSummaryRow;
import com.susukkang.fgc.validation.dto.ValidationTargetListRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IF-API-47 상세 조회 SQL 통합테스트 (FUN-042·043).
 * 로컬 db(데모 시드)에 결과 행을 넣어 실행 스코프 집계·조인·정렬 규칙을 SQL 레벨에서 검증
 * — 뷰(vw_journal_imbalance)·대사 결과를 그냥 세면 다른 실행 것까지 섞인다(화면정의서 :1462).
 */
@SpringBootTest
@Transactional
class ValidationRunDetailMapperIntegrationTest {

    @Autowired
    private ValidationRunDetailMapper mapper;

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

    // 데모 시드(V6_1)가 같은 지급단계의 룰셋을 여러 개 가질 수 있어 단건 조회 대신 MIN 을 쓴다
    private Long capRuleSetId(String paymentStage) {
        return jdbcTemplate.queryForObject(
                "SELECT MIN(cap_rule_set_id) FROM fgc.cap_rule_set WHERE payment_stage = ?",
                Long.class, paymentStage);
    }

    // guard_run_lifecycle(V7)이 INSERT 시 CREATED만 허용한다 — 상태는 UPDATE로 전이시킨다.
    private Long insertValidationRun(LocalDate month, int runNo, String status) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                VALUES (?, ?, 'MONTHLY', 'CREATED')
                RETURNING validation_run_id
                """, Long.class, month, runNo);
        if (!"CREATED".equals(status)) {
            jdbcTemplate.update("UPDATE fgc.validation_run SET status='RUNNING' WHERE validation_run_id=?", id);
            if ("COMPLETED".equals(status)) {
                jdbcTemplate.update(
                        "UPDATE fgc.validation_run SET status='COMPLETED', current_step=8 WHERE validation_run_id=?", id);
            }
        }
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

    /** balanced=false 면 차변만 있는 분개 — vw_journal_imbalance 에 잡힌다(DRAFT 라 POSTED 차단과 무관). */
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

    // uq_reconciliation_run 이 (settlement_month, payment_stage, insurer_id, validation_run_id)로
    // 유일해서 실행당 대사 실행은 한 번만 만들고, 결과는 그 아래에 여러 건 넣는다
    private Long insertReconciliationRun(Long runId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_run (settlement_month, payment_stage, validation_run_id)
                VALUES ('2026-07-01', 'INSURER_TO_GA', ?)
                RETURNING reconciliation_run_id
                """, Long.class, runId);
    }

    private void insertReconciliationResult(Long reconRunId, String matchGroupKey, String resultType) {
        jdbcTemplate.update("""
                INSERT INTO fgc.reconciliation_result (reconciliation_run_id, match_group_key, result_type)
                VALUES (?, ?, ?)
                """, reconRunId, matchGroupKey, resultType);
    }

    @Test
    void findHeaderByIdJoinsLoginIdsAndReturnsNullForMissing() {
        Long runId = insertValidationRun(LocalDate.of(2031, 1, 1), 1, "CREATED");

        ValidationRunListRow header = mapper.findHeaderById(runId);

        assertThat(header).isNotNull();
        assertThat(header.getValidationMonth()).isEqualTo(LocalDate.of(2031, 1, 1));
        assertThat(header.getRunNo()).isEqualTo(1);
        assertThat(header.getStatus()).isEqualTo("CREATED");
        assertThat(header.getCurrentStep()).isZero();
        assertThat(header.getTriggeredBy()).isNull(); // triggered_by 미지정 → login_id 도 null

        assertThat(mapper.findHeaderById(-1L)).isNull();
    }

    @Test
    void findTargetsOrdersReviewRequiredFirstAndJoinsProductInfo() {
        Long runId = insertValidationRun(LocalDate.of(2031, 2, 1), 1, "CREATED");
        Long selected = contractId("FGC-FGL01-202607-0001");
        Long excluded = contractId("FGC-FGL01-202607-0002");
        Long review = contractId("FGC-FGL01-202607-0004");
        insertTarget(runId, selected, "SELECTED", null);
        insertTarget(runId, excluded, "EXCLUDED", "취소 계약");
        insertTarget(runId, review, "REVIEW_REQUIRED", "환급률표 누락");

        List<ValidationTargetListRow> targets = mapper.findTargets(runId, 200);

        assertThat(targets).hasSize(3);
        assertThat(targets.get(0).getSelectionStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(targets.get(1).getSelectionStatus()).isEqualTo("EXCLUDED");
        assertThat(targets.get(2).getSelectionStatus()).isEqualTo("SELECTED");
        assertThat(targets.get(0).getContractNo()).isEqualTo("FGC-FGL01-202607-0004");
        assertThat(targets.get(0).getProductName()).isNotBlank();
        assertThat(targets.get(0).getOfferingVersion()).isNotBlank();
        assertThat(targets.get(0).getSelectionReason()).isEqualTo("환급률표 누락");
    }

    @Test
    void summarizeScopesEveryBlockToTheGivenRun() {
        Long runId = insertValidationRun(LocalDate.of(2031, 3, 1), 1, "RUNNING");
        Long otherRunId = insertValidationRun(LocalDate.of(2031, 4, 1), 1, "RUNNING");
        Long c1 = contractId("FGC-FGL01-202607-0001");
        Long c2 = contractId("FGC-FGL01-202607-0002");

        insertTarget(runId, c1, "SELECTED", null);
        insertTarget(runId, c2, "REVIEW_REQUIRED", "검토");
        insertCapCheck(runId, c1, "INSURER_TO_GA", "VIOLATION");
        insertCapCheck(runId, c2, "INSURER_TO_GA", "WARNING");
        insertArbitrageCheck(runId, c1, "CANDIDATE");
        insertJournal(runId, "VRUN-DTL-JRN-1", true);
        insertJournal(runId, "VRUN-DTL-JRN-2", false); // 불균형 1건
        Long reconRunId = insertReconciliationRun(runId);
        insertReconciliationResult(reconRunId, "VRUN-DTL-REC-1", "MATCHED");
        insertReconciliationResult(reconRunId, "VRUN-DTL-REC-2", "AMOUNT_DIFFERENCE");

        // 다른 실행의 결과는 집계에 섞이면 안 된다
        insertTarget(otherRunId, c1, "SELECTED", null);
        insertCapCheck(otherRunId, c1, "INSURER_TO_GA", "VIOLATION");
        insertJournal(otherRunId, "VRUN-DTL-JRN-9", false);
        insertReconciliationResult(insertReconciliationRun(otherRunId), "VRUN-DTL-REC-9", "DUPLICATE");

        ValidationRunResultSummaryRow summary = mapper.summarize(runId);

        assertThat(summary.getTargetSelectedCount()).isEqualTo(1);
        assertThat(summary.getTargetExcludedCount()).isZero();
        assertThat(summary.getTargetReviewRequiredCount()).isEqualTo(1);
        assertThat(summary.getCapCheckedCount()).isEqualTo(2);
        assertThat(summary.getCapViolationCount()).isEqualTo(1);
        assertThat(summary.getCapWarningCount()).isEqualTo(1);
        assertThat(summary.getArbitrageCheckedCount()).isEqualTo(1);
        assertThat(summary.getArbitrageCandidateCount()).isEqualTo(1);
        assertThat(summary.getJournalCount()).isEqualTo(2);
        assertThat(summary.getJournalImbalanceCount()).isEqualTo(1);
        assertThat(summary.getReconciliationResultCount()).isEqualTo(2);
        assertThat(summary.getReconciliationMismatchCount()).isEqualTo(1);
    }

    @Test
    void summarizeReturnsAllZerosForRunWithoutResults() {
        Long runId = insertValidationRun(LocalDate.of(2031, 5, 1), 1, "CREATED");

        ValidationRunResultSummaryRow summary = mapper.summarize(runId);

        assertThat(summary.getTargetSelectedCount()).isZero();
        assertThat(summary.getCapCheckedCount()).isZero();
        assertThat(summary.getArbitrageCheckedCount()).isZero();
        assertThat(summary.getJournalCount()).isZero();
        assertThat(summary.getReconciliationResultCount()).isZero();
    }
}
