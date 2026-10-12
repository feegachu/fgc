package com.susukkang.fgc.reconciliation.repository;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.reconciliation.dto.GaFcExpectedSourceRow;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import com.susukkang.fgc.reconciliation.service.GaFcReconciliationMatcher;
import com.susukkang.fgc.reconciliation.service.ReconciliationResultPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #518 대사 범위 확장(#516)으로 함께 대사되는 비선정 계약의 판정 근거.
 * ② 지급예정일 시점 실효·해지 회차는 예상 원천에서 빠진다(인터페이스정의서 §9-5, PAY_IF_ACTIVE_ON_DUE_DATE).
 * ① 검토필요로 보류된 대상은 분류 문맥에 신호로 실린다.
 * 세션 타임존을 UTC로 바꿔도 효력일 경계는 Asia/Seoul 자정이어야 한다.
 */
@SpringBootTest
@Transactional
class ReconciliationNonSelectedContractIntegrationTest {

    private static final LocalDate MONTH = LocalDate.of(2098, 5, 1);

    @Autowired
    private GaFcReconciliationRepository gaFcSources;
    @Autowired
    private ReconciliationResultRepository results;
    @Autowired
    private GaFcReconciliationMatcher gaFcMatcher;
    @Autowired
    private ReconciliationResultPersistenceService persistenceService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void useUtcSession() {
        jdbcTemplate.execute("SET LOCAL TIME ZONE 'UTC'");
    }

    @Test
    void 지급예정일_시점에_실효된_회차는_예상에서_빠지고_실효_전_회차는_남는다() {
        Long contractId = insertContract("LAPSED");
        insertEvent(contractId, 1, "ACTIVE", "2098-01-15 00:00+09");
        insertEvent(contractId, 2, "LAPSED", "2098-05-10 00:00+09");
        Long before = insertLine(contractId, 1, MONTH.withDayOfMonth(9));
        Long onLapseDay = insertLine(contractId, 2, MONTH.withDayOfMonth(10));
        Long after = insertLine(contractId, 3, MONTH.withDayOfMonth(25));

        assertThat(expectedLineIds(contractId)).contains(before).doesNotContain(onLapseDay, after);
    }

    @Test
    void 실효_뒤_부활한_계약은_실효_기간_회차만_빠진다() {
        Long contractId = insertContract("REVIVED");
        insertEvent(contractId, 1, "ACTIVE", "2098-01-15 00:00+09");
        insertEvent(contractId, 2, "LAPSED", "2098-05-10 00:00+09");
        insertEvent(contractId, 3, "REVIVED", "2098-05-20 00:00+09");
        Long lapsedPeriod = insertLine(contractId, 1, MONTH.withDayOfMonth(15));
        Long revived = insertLine(contractId, 2, MONTH.withDayOfMonth(25));

        assertThat(expectedLineIds(contractId)).contains(revived).doesNotContain(lapsedPeriod);
    }

    @Test
    void 상태_사건이_없으면_현재_상태가_실효여도_예상에_남긴다() {
        Long contractId = insertContract("LAPSED");
        Long line = insertLine(contractId, 1, MONTH.withDayOfMonth(25));

        assertThat(expectedLineIds(contractId)).contains(line);
    }

    @Test
    void 청약철회_계약은_상태_사건과_무관하게_예상에서_빠진다() {
        Long contractId = insertContract("CANCELLED");
        insertEvent(contractId, 1, "ACTIVE", "2098-01-15 00:00+09");
        Long line = insertLine(contractId, 1, MONTH.withDayOfMonth(25));

        assertThat(expectedLineIds(contractId)).doesNotContain(line);
    }

    @Test
    void 최신_상태_사건이_현재_상태와_다르면_이력을_믿지_않고_예상에_남긴다() {
        Long contractId = insertContract("ACTIVE");
        insertEvent(contractId, 1, "ACTIVE", "2098-01-15 00:00+09");
        insertEvent(contractId, 2, "LAPSED", "2098-05-10 00:00+09");
        Long line = insertLine(contractId, 1, MONTH.withDayOfMonth(25));

        assertThat(expectedLineIds(contractId)).contains(line);
    }

    /** PR #520 리뷰: 부활 계약도 실효 기간 회차 지급은 비유효다. 현재 상태(REVIVED)에 막히지 않아야 한다. */
    @Test
    void 부활_계약의_실효_기간_회차_지급은_비유효_계약_지급이다() {
        Long contractId = insertContract("REVIVED");
        insertEvent(contractId, 1, "ACTIVE", "2098-01-15 00:00+09");
        insertEvent(contractId, 2, "LAPSED", "2098-05-10 00:00+09");
        insertEvent(contractId, 3, "REVIVED", "2098-05-20 00:00+09");
        LocalDate lapsedPeriod = MONTH.withDayOfMonth(15);
        insertLine(contractId, 1, lapsedPeriod);
        insertConfirmedPayment(contractId, 1, lapsedPeriod);
        Long insurerId = jdbcTemplate.queryForObject(
                "SELECT insurer_id FROM fgc.insurance_contract WHERE contract_id = ?", Long.class, contractId);
        Long runId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_run (settlement_month, payment_stage, insurer_id)
                VALUES (?, 'GA_TO_FC', ?)
                RETURNING reconciliation_run_id
                """, Long.class, MONTH, insurerId);
        ReconciliationExecutionRequest request = new ReconciliationExecutionRequest(
                runId, null, MONTH, PaymentStage.GA_TO_FC, insurerId, null);

        persistenceService.persist(request, gaFcMatcher.match(request).stream()
                .filter(candidate -> contractId.equals(candidate.contractId()))
                .toList());

        assertThat(jdbcTemplate.queryForObject("""
                SELECT primary_reason_code FROM fgc.reconciliation_result
                 WHERE reconciliation_run_id = ? AND contract_id = ?
                """, String.class, runId, contractId)).isEqualTo("INVALID_CONTRACT_PAYMENT");
        assertThat(invalidOn(contractId, MONTH.withDayOfMonth(25))).isFalse();
    }

    @Test
    void 이력을_믿을_수_없으면_현재_상태가_정상인_계약의_지급은_비유효가_아니다() {
        Long contractId = insertContract("ACTIVE");
        insertEvent(contractId, 1, "ACTIVE", "2098-01-15 00:00+09");
        insertEvent(contractId, 2, "LAPSED", "2098-05-10 00:00+09");

        assertThat(invalidOn(contractId, MONTH.withDayOfMonth(25))).isFalse();
    }

    @Test
    void 이_검증_실행에서_검토필요로_보류된_대상만_보류_신호가_켜진다() {
        Long held = insertContract("ACTIVE");
        Long selected = insertContract("ACTIVE");
        Long validationRunId = insertValidationRun();
        insertTarget(validationRunId, held, "REVIEW_REQUIRED");
        insertTarget(validationRunId, selected, "SELECTED");

        assertThat(reviewRequired(validationRunId, held)).isTrue();
        assertThat(reviewRequired(validationRunId, selected)).isFalse();
        assertThat(reviewRequired(null, held)).isFalse();
        assertThat(reviewRequired(validationRunId + 1_000_000, held)).isFalse();
    }

    private List<Long> expectedLineIds(Long contractId) {
        Long insurerId = jdbcTemplate.queryForObject(
                "SELECT insurer_id FROM fgc.insurance_contract WHERE contract_id = ?", Long.class, contractId);
        return gaFcSources.findExpectedSources(MONTH, insurerId).stream()
                .filter(row -> contractId.equals(row.getContractId()))
                .map(GaFcExpectedSourceRow::getScheduleLineId)
                .toList();
    }

    private boolean reviewRequired(Long validationRunId, Long contractId) {
        return results.findClassificationContext(validationRunId, contractId, null, null, List.of(), null)
                .isTargetReviewRequired();
    }

    private boolean invalidOn(Long contractId, LocalDate dueDate) {
        return results.findClassificationContext(null, contractId, null, null, List.of(), dueDate)
                .isInvalidContractPayment();
    }

    private void insertConfirmedPayment(Long contractId, int installmentNo, LocalDate dueDate) {
        Long transactionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    payment_stage, source_type, source_business_key, source_contract_id,
                    recipient_agent_id, commission_item_id, installment_no,
                    settlement_month, due_date, amount, cashflow_type, status
                )
                SELECT 'GA_TO_FC', 'GA_MANUAL_PAYMENT', ?, c.contract_id, c.agent_id,
                       (SELECT commission_item_id FROM fgc.commission_item
                         WHERE cashflow_type = 'PAYMENT' ORDER BY commission_item_id LIMIT 1),
                       ?, ?, ?, 5000, 'PAYMENT', 'DRAFT'
                  FROM fgc.insurance_contract c
                 WHERE c.contract_id = ?
                RETURNING commission_transaction_id
                """, Long.class, "IT-518-" + UUID.randomUUID(), installmentNo, MONTH, dueDate, contractId);
        jdbcTemplate.update("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id, attribution_seq, attribution_scope, contract_id,
                    attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, attribution_method
                ) VALUES (?, 1, 'CONTRACT', ?, ?, ?, 5000, 'INCLUDED', 'DIRECT')
                """, transactionId, contractId, dueDate, MONTH);
        jdbcTemplate.update(
                "UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = ?",
                transactionId);
    }

    private Long insertContract(String currentStatus) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date,
                    agent_id, organization_id, premium_per_cycle_amount,
                    first_premium_amount, monthly_equivalent_first_premium, payment_term_months,
                    current_status
                )
                SELECT p.insurer_id, po.product_offering_id, ?, DATE '2098-01-15',
                       a.agent_id, a.organization_id, 100000, 100000, 100000, 120, ?
                  FROM fgc.product p
                  JOIN fgc.product_offering po ON po.product_id = p.product_id
                                              AND po.offering_version = '2026-CURRENT-A'
                  CROSS JOIN LATERAL (
                       SELECT agent_id, organization_id FROM fgc.agent ORDER BY agent_id LIMIT 1
                  ) a
                 WHERE p.standard_product_code = 'STD-LIFE-A'
                RETURNING contract_id
                """, Long.class, "IT-518-" + UUID.randomUUID(), currentStatus);
    }

    private void insertEvent(Long contractId, int seq, String newStatus, String effectiveAt) {
        jdbcTemplate.update("""
                INSERT INTO fgc.contract_status_event (
                    contract_id, event_seq, previous_status, new_status,
                    effective_at, received_at, source_system, source_event_key, data_origin
                )
                VALUES (?, ?, NULL, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 'IT', ?, 'MANUAL')
                """, contractId, seq, newStatus, effectiveAt, effectiveAt, "IT-518-" + contractId + "-" + seq);
    }

    private Long insertLine(Long contractId, int installmentNo, LocalDate dueDate) {
        Long headerId = jdbcTemplate.query("""
                SELECT schedule_header_id FROM fgc.schedule_header
                 WHERE contract_id = ? AND payment_stage = 'GA_TO_FC' AND schedule_version_no = 9518
                """, rs -> rs.next() ? rs.getLong(1) : null, contractId);
        if (headerId == null) {
            headerId = jdbcTemplate.queryForObject("""
                    INSERT INTO fgc.schedule_header
                        (contract_id, payment_stage, policy_version_id, schedule_version_no,
                         schedule_purpose, schedule_regime, active_yn)
                    VALUES (?, 'GA_TO_FC', (SELECT MIN(policy_version_id) FROM fgc.policy_version),
                            9518, 'OPERATIONAL', 'CURRENT', TRUE)
                    RETURNING schedule_header_id
                    """, Long.class, contractId);
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_line
                    (schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                     commission_item_id, beneficiary_agent_id, basis_code, basis_amount,
                     calculation_type, fixed_amount, expected_amount)
                SELECT ?, ?, ?, ?, ?,
                       (SELECT commission_item_id FROM fgc.commission_item
                         WHERE cashflow_type = 'PAYMENT' ORDER BY commission_item_id LIMIT 1),
                       c.agent_id, 'IT_518', 5000, 'FIXED', 5000, 5000
                  FROM fgc.insurance_contract c
                 WHERE c.contract_id = ?
                RETURNING schedule_line_id
                """, Long.class, headerId, installmentNo, installmentNo, installmentNo, dueDate, contractId);
    }

    private Long insertValidationRun() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (?, ?, 'PRE_CONFIRM')
                RETURNING validation_run_id
                """, Long.class, MONTH, Math.abs(UUID.randomUUID().hashCode() % 1_000_000) + 100_000);
    }

    private void insertTarget(Long validationRunId, Long contractId, String selectionStatus) {
        jdbcTemplate.update("""
                INSERT INTO fgc.validation_target (validation_run_id, contract_id, product_offering_id, selection_status)
                SELECT ?, contract_id, product_offering_id, ? FROM fgc.insurance_contract WHERE contract_id = ?
                """, validationRunId, selectionStatus, contractId);
    }
}
