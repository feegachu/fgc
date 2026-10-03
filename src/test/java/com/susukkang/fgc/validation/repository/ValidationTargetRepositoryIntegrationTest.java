package com.susukkang.fgc.validation.repository;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.validation.dto.ValidationScheduleState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ValidationTargetSelectionMapperIntegrationTest(MyBatis)와 같은 시나리오를
 * ValidationTargetRepository(JPA, 네이티브 벌크 insert)로 검증한다.
 */
@SpringBootTest
@Transactional
class ValidationTargetRepositoryIntegrationTest {

    @Autowired
    private ValidationTargetRepository validationTargetRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void fgcFun042InsertTargetsSelectsSeedContractsByStandardProductCode() {
        LocalDate validationMonth = LocalDate.of(2026, 7, 1);
        Long runId = insertValidationRun(validationMonth);

        int inserted = validationTargetRepository.insertTargets(runId, validationMonth, LocalDate.of(2026, 7, 31));

        assertThat(inserted).isPositive();
        List<String> selectedContractNos = jdbcTemplate.queryForList("""
                SELECT ic.contract_no
                  FROM fgc.validation_target vt
                  JOIN fgc.insurance_contract ic ON ic.contract_id = vt.contract_id
                 WHERE vt.validation_run_id = ? AND vt.selection_status = 'SELECTED'
                """, String.class, runId);
        long selected = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.validation_target
                 WHERE validation_run_id = ? AND selection_status = 'SELECTED'
                """, Long.class, runId);
        long productCodeMismatch = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.validation_target
                 WHERE validation_run_id = ? AND selection_reason = '상품코드 불일치'
                """, Long.class, runId);
        long selectedWithoutTable = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.validation_target
                 WHERE validation_run_id = ? AND selection_status = 'SELECTED'
                   AND refund_rate_table_id IS NULL
                """, Long.class, runId);
        long withoutStandardCodeEvidence = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.validation_target
                 WHERE validation_run_id = ?
                   AND (snapshot ->> 'standardProductCode' IS NULL
                        OR snapshot ->> 'insurerProductCode' IS NOT NULL)
                """, Long.class, runId);

        assertThat(selectedContractNos).containsExactlyInAnyOrder(
                "FGC-FGL01-202607-0001",
                "FGC-FGL01-202607-0002",
                "FGC-FGL01-202607-0003",
                "FGC-FGL01-202607-0004",
                "FGC-FGL01-202607-0005",
                "FGC-FGL02-202605-0001");
        assertThat(selected).isPositive();
        assertThat(productCodeMismatch).isZero();
        assertThat(selectedWithoutTable).isZero();
        assertThat(withoutStandardCodeEvidence).isZero();
    }

    @Test
    void fgc332SavContractIsSelectedWithSeededRefundRateTable() {
        Long contractId = insertSavContract();
        Long runId = insertValidationRun(LocalDate.of(2098, 3, 1));

        validationTargetRepository.insertTargets(runId, LocalDate.of(2098, 3, 1), LocalDate.of(2098, 3, 31));

        var target = jdbcTemplate.queryForMap("""
                SELECT selection_status, selection_reason, refund_rate_table_id
                  FROM fgc.validation_target
                 WHERE validation_run_id = ? AND contract_id = ?
                """, runId, contractId);
        assertThat(target.get("selection_status")).isEqualTo("SELECTED");
        assertThat(target.get("selection_reason")).isEqualTo("월 통합검증 대상");
        assertThat(target.get("refund_rate_table_id")).isNotNull();
    }

    @Test
    void insertTargetsIsIdempotentOnRetryViaOnConflictDoNothing() {
        LocalDate validationMonth = LocalDate.of(2026, 7, 1);
        Long runId = insertValidationRun(validationMonth);
        validationTargetRepository.insertTargets(runId, validationMonth, LocalDate.of(2026, 7, 31));
        long firstCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.validation_target WHERE validation_run_id = ?", Long.class, runId);

        int secondInsert = validationTargetRepository.insertTargets(runId, validationMonth, LocalDate.of(2026, 7, 31));

        assertThat(secondInsert).isZero();
        long secondCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.validation_target WHERE validation_run_id = ?", Long.class, runId);
        assertThat(secondCount).isEqualTo(firstCount);
    }

    @Test
    void selectsOnlySelectedContractsFromRequestedRunInContractOrder() {
        Long requestedRunId = insertValidationRun(LocalDate.of(2097, 1, 1));
        Long otherRunId = insertValidationRun(LocalDate.of(2097, 2, 1));
        List<Long> contractIds = jdbcTemplate.queryForList(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 3",
                Long.class);
        assertThat(contractIds).hasSize(3);

        insertTarget(requestedRunId, contractIds.get(2), "SELECTED");
        insertTarget(requestedRunId, contractIds.get(0), "SELECTED");
        insertTarget(requestedRunId, contractIds.get(1), "REVIEW_REQUIRED");
        insertTarget(otherRunId, contractIds.get(1), "SELECTED");

        List<Long> selected = validationTargetRepository.selectSelectedContractIds(requestedRunId);

        assertThat(selected).containsExactly(contractIds.get(0), contractIds.get(2));
    }

    /**
     * #380 Phase 4 — ValidationTargetRepository.selectScheduleStates(unnest + ROW() 기반
     * DISTINCT COUNT를 네이티브 쿼리로 옮긴 메서드)가 선별된 계약의 두 지급 단계를 모두
     * 반환하는지 검증한다. 구 MyBatis ValidationScheduleMapper는 이 전환 완료 후 다른
     * 패키지에서 참조가 없어 삭제되었다 — 이 테스트가 그 검증 책임을 이어받는다.
     */
    @Test
    void selectScheduleStatesReturnsBothPaymentStagesForSelectedContract() {
        LocalDate validationMonth = LocalDate.of(2098, 7, 1);
        Long validationRunId = insertValidationRun(validationMonth);
        Long contractId = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1",
                Long.class);
        insertTarget(validationRunId, contractId, "SELECTED");

        List<ValidationScheduleState> states = validationTargetRepository.selectScheduleStates(validationRunId);

        assertThat(states).hasSize(2);
        assertThat(states).extracting(ValidationScheduleState::getPaymentStage)
                .containsExactlyInAnyOrder(PaymentStage.INSURER_TO_GA, PaymentStage.GA_TO_FC);
        assertThat(states).allSatisfy(state -> {
            assertThat(state.getContractId()).isEqualTo(contractId);
            assertThat(state.getActiveHeaderCount()).isNotNull();
            assertThat(state.getLineCount()).isNotNull();
            assertThat(state.getDistinctLineCount()).isNotNull();
        });
    }

    @Test
    void selectScheduleStatesSumsScheduleAmountsAfterRoundingEachLineToWon() {
        LocalDate validationMonth = LocalDate.of(2098, 8, 1);
        Long validationRunId = insertValidationRun(validationMonth);
        Long contractId = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1",
                Long.class);
        LocalDate contractDate = jdbcTemplate.queryForObject(
                "SELECT contract_date FROM fgc.insurance_contract WHERE contract_id = ?",
                LocalDate.class, contractId);
        Long policyVersionId = jdbcTemplate.queryForObject(
                "SELECT policy_version_id FROM fgc.policy_version WHERE status = 'ACTIVE' ORDER BY policy_version_id LIMIT 1",
                Long.class);
        Long commissionItemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1",
                Long.class);
        insertTarget(validationRunId, contractId, "SELECTED");
        jdbcTemplate.update("""
                UPDATE fgc.schedule_header
                   SET active_yn = false
                 WHERE contract_id = ?
                   AND payment_stage = 'GA_TO_FC'
                   AND schedule_purpose = 'OPERATIONAL'
                   AND active_yn = true
                """, contractId);
        Long scheduleHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header (
                    contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_regime, schedule_purpose, active_yn
                )
                VALUES (?, 'GA_TO_FC', ?, 999, 'CURRENT', 'OPERATIONAL', true)
                RETURNING schedule_header_id
                """, Long.class, contractId, policyVersionId);
        jdbcTemplate.update("""
                INSERT INTO fgc.schedule_line (
                    schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, basis_code, basis_amount, calculation_type,
                    rate_pct, expected_amount
                ) VALUES
                    (?, 1, 1, 1, ?, ?, 'TEST_AMOUNT', 10.40, 'RATE', 100.000000, 10.40),
                    (?, 2, 2, 1, ?, ?, 'TEST_AMOUNT', 10.40, 'RATE', 100.000000, 10.40)
                """, scheduleHeaderId, contractDate, commissionItemId,
                scheduleHeaderId, contractDate, commissionItemId);

        ValidationScheduleState gaToFc = validationTargetRepository.selectScheduleStates(validationRunId).stream()
                .filter(state -> state.getPaymentStage() == PaymentStage.GA_TO_FC)
                .findFirst()
                .orElseThrow();

        assertThat(gaToFc.getTotalAmount()).isEqualByComparingTo(new BigDecimal("20"));
    }

    private Long insertSavContract() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date,
                    agent_id, organization_id, premium_per_cycle_amount,
                    first_premium_amount, monthly_equivalent_first_premium, payment_term_months
                )
                SELECT p.insurer_id, po.product_offering_id, 'IT-332-SAV-0001', DATE '2098-02-15',
                       a.agent_id, a.organization_id, 200000, 200000, 200000, 120
                  FROM fgc.product p
                  JOIN fgc.product_offering po ON po.product_id = p.product_id
                                              AND po.offering_version = '2026-CURRENT-A'
                                              AND po.channel_code = 'FACE_TO_FACE'
                  CROSS JOIN LATERAL (
                       SELECT agent_id, organization_id FROM fgc.agent ORDER BY agent_id LIMIT 1
                  ) a
                 WHERE p.standard_product_code = 'STD-SAV-A'
                RETURNING contract_id
                """, Long.class);
    }

    private Long insertValidationRun(LocalDate validationMonth) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (
                    validation_month, run_no, run_type, status
                )
                SELECT ?, COALESCE(MAX(run_no), 0) + 1, 'MONTHLY', 'CREATED'
                  FROM fgc.validation_run
                 WHERE validation_month = ?
                RETURNING validation_run_id
                """, Long.class, validationMonth, validationMonth);
    }

    private void insertTarget(Long validationRunId, Long contractId, String status) {
        jdbcTemplate.update("""
                INSERT INTO fgc.validation_target (
                    validation_run_id, contract_id, product_offering_id,
                    selection_status, selection_reason
                )
                SELECT ?, contract_id, product_offering_id, ?, 'TEST'
                  FROM fgc.insurance_contract
                 WHERE contract_id = ?
                """, validationRunId, status, contractId);
    }
}
