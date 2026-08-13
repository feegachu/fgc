package com.susukkang.fgc.reconciliation;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;
import com.susukkang.fgc.reconciliation.dto.GaFcMatchCandidate;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import com.susukkang.fgc.reconciliation.service.GaFcReconciliationMatcher;
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
 * 설명 : FGC-FUN-048-03 실제 PostgreSQL에서 GA→FC 대사 원천 필터와 매칭 결과를 검증
 *
 * @author yslee
 * @since 2026-08-13
 * @version 1.2
 */
@SpringBootTest
@Transactional
class GaFcReconciliationIntegrationTest {

    private static final LocalDate TEST_MONTH = LocalDate.of(2098, 9, 1);

    @Autowired
    private GaFcReconciliationMatcher matcher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 기존_DB에서_GA_TO_FC_운영_스케줄과_FUN_065_확정_지급만_매칭한다() {
        Long insurerId = id("SELECT insurer_id FROM fgc.insurer WHERE active_yn = true ORDER BY insurer_id LIMIT 1");
        Long contractId = id(
                "SELECT contract_id FROM fgc.insurance_contract WHERE insurer_id = ? ORDER BY contract_id LIMIT 1",
                insurerId);
        Long policyVersionId = id("SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1");
        Long commissionItemId = id("""
                SELECT commission_item_id
                  FROM fgc.commission_item
                 WHERE cashflow_type = 'PAYMENT'
                 ORDER BY commission_item_id
                 LIMIT 1
                """);
        Long agentId = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", contractId);

        Long scheduleLineId = insertSchedule(contractId, policyVersionId, commissionItemId, agentId, 1, "650000");
        Long expectedJournalId = insertPostedJournal(
                "EXPECTED_FC_PAYOUT", "SCHEDULE_LINE", scheduleLineId,
                contractId, agentId, commissionItemId, "650000",
                "EXPECTED_PAYOUT_EXPENSE", "EXPECTED_PAYOUT_PAYABLE");

        PaymentInsert confirmed = insertPayment(
                contractId, policyVersionId, commissionItemId, agentId,
                1, "650000", "CONFIRMED", "MATCHED");
        Long actualJournalId = insertPostedJournal(
                "CONFIRMED_FC_PAYOUT", "COMMISSION_TRANSACTION", confirmed.transactionId(),
                contractId, agentId, commissionItemId, "650000",
                "CONFIRMED_PAYOUT_EXPENSE", "CONFIRMED_PAYOUT_PAYABLE");

        PaymentInsert draft = insertPayment(
                contractId, policyVersionId, commissionItemId, agentId,
                1, "777000", "DRAFT", "DRAFT-EXCLUDED");
        insertPostedJournal(
                "CONFIRMED_FC_PAYOUT", "COMMISSION_TRANSACTION", draft.transactionId(),
                contractId, agentId, commissionItemId, "777000",
                "CONFIRMED_PAYOUT_EXPENSE", "CONFIRMED_PAYOUT_PAYABLE");

        // 2026-08-13 yslee - FGC-FUN-048-03 원천 조회 제외 조건 회귀 검증
        // 기존 코드: DRAFT 지급 제외만 검증해 스케줄 활성·목적·지급단계 필터 회귀를 발견하지 못함
        // 문제: 비활성·비운영 스케줄이나 다른 지급단계의 확정 건이 같은 매칭 그룹에 섞일 수 있음
        // 개선: 각 제외 원천을 같은 키로 적재하고 결과 식별자에 포함되지 않는지 검증
        Long inactiveScheduleLineId = insertSchedule(
                contractId, policyVersionId, commissionItemId, agentId,
                1, "888000", "OPERATIONAL", null, false, 901);
        Long comparisonScheduleLineId = insertSchedule(
                contractId, policyVersionId, commissionItemId, agentId,
                1, "999000", "COMPARISON", "IT-048-03-COMPARISON", true, 902);
        PaymentInsert insurerToGaConfirmed = insertPayment(
                contractId, policyVersionId, commissionItemId, agentId,
                1, "111000", "CONFIRMED", "INSURER-TO-GA-EXCLUDED",
                "INSURER_TO_GA", "INSURER_STATEMENT");

        List<GaFcMatchCandidate> results = matcher.match(new ReconciliationExecutionRequest(
                99L, 88L, TEST_MONTH, PaymentStage.GA_TO_FC, insurerId, null));

        assertThat(results).hasSize(1);
        GaFcMatchCandidate result = results.getFirst();
        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.MATCHED);
        assertThat(result.expectedAgentId()).isEqualTo(agentId);
        assertThat(result.actualAgentId()).isEqualTo(agentId);
        assertThat(result.scheduleLineIds()).containsExactly(scheduleLineId);
        assertThat(result.scheduleLineIds())
                .doesNotContain(inactiveScheduleLineId, comparisonScheduleLineId);
        assertThat(result.transactionAttributionIds()).containsExactly(confirmed.attributionId());
        assertThat(result.transactionAttributionIds())
                .doesNotContain(draft.attributionId(), insurerToGaConfirmed.attributionId());
        assertThat(result.expectedJournalHeaderIds()).containsExactly(expectedJournalId);
        assertThat(result.actualJournalHeaderIds()).containsExactly(actualJournalId);
    }

    // 2026-08-13 yslee - DB 원천의 수취 설계사·회차 비교 불가 상태 검증
    // 기존 코드: FUN-065 지급 건과 GA→FC 스케줄의 정규화 매칭 통합 시나리오가 없음
    // 문제: 실제 회차가 NULL인 기존 확정 지급 건을 예정일만으로 정상일치 처리할 위험이 있음
    // 개선: 실제 PostgreSQL 조회에서도 회차 누락을 유지하고 REVIEW_REQUIRED로 판정되는지 확인
    @Test
    void 확정_지급_건의_회차가_없으면_REVIEW_REQUIRED다() {
        Long insurerId = id("SELECT insurer_id FROM fgc.insurer WHERE active_yn = true ORDER BY insurer_id LIMIT 1");
        Long contractId = id(
                "SELECT contract_id FROM fgc.insurance_contract WHERE insurer_id = ? ORDER BY contract_id LIMIT 1",
                insurerId);
        Long policyVersionId = id("SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1");
        Long commissionItemId = id("""
                SELECT commission_item_id
                  FROM fgc.commission_item
                 WHERE cashflow_type = 'PAYMENT'
                 ORDER BY commission_item_id
                 LIMIT 1
                """);
        Long agentId = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", contractId);

        Long scheduleLineId = insertSchedule(contractId, policyVersionId, commissionItemId, agentId, 13, "650000");
        insertPostedJournal(
                "EXPECTED_FC_PAYOUT", "SCHEDULE_LINE", scheduleLineId,
                contractId, agentId, commissionItemId, "650000",
                "EXPECTED_PAYOUT_EXPENSE", "EXPECTED_PAYOUT_PAYABLE");
        PaymentInsert confirmed = insertPayment(
                contractId, policyVersionId, commissionItemId, agentId,
                null, "650000", "CONFIRMED", "NULL-INSTALLMENT");
        insertPostedJournal(
                "CONFIRMED_FC_PAYOUT", "COMMISSION_TRANSACTION", confirmed.transactionId(),
                contractId, agentId, commissionItemId, "650000",
                "CONFIRMED_PAYOUT_EXPENSE", "CONFIRMED_PAYOUT_PAYABLE");

        GaFcMatchCandidate result = matcher.match(new ReconciliationExecutionRequest(
                99L, 88L, TEST_MONTH, PaymentStage.GA_TO_FC, insurerId, null)).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.REVIEW_REQUIRED);
        assertThat(result.installmentNo()).isEqualTo(13);
        assertThat(result.actualInstallmentNo()).isNull();
    }

    // 2026-08-13 yslee - FUN-046 원장 연동 전 확정 지급 건 대사 회귀 검증
    // 기존 코드: journal_header를 필수 JOIN하여 POSTED 분개가 없는 스케줄·확정 지급 건을 조회에서 제거
    // 문제: FUN-065 확정은 완료됐지만 FUN-046 분개가 아직 생성되지 않은 실제 지급 건이 대사 대상에서 사라짐
    // 개선: 원천 매칭은 계속 수행하고 원장 ID는 존재하는 경우에만 후속 저장 후보에 보존
    @Test
    void POSTED_원장_연동_전에도_스케줄과_확정_지급_건을_매칭한다() {
        Long insurerId = id("SELECT insurer_id FROM fgc.insurer WHERE active_yn = true ORDER BY insurer_id LIMIT 1");
        Long contractId = id(
                "SELECT contract_id FROM fgc.insurance_contract WHERE insurer_id = ? ORDER BY contract_id LIMIT 1",
                insurerId);
        Long policyVersionId = id("SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1");
        Long commissionItemId = id("""
                SELECT commission_item_id
                  FROM fgc.commission_item
                 WHERE cashflow_type = 'PAYMENT'
                 ORDER BY commission_item_id
                 LIMIT 1
                """);
        Long agentId = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", contractId);

        Long scheduleLineId = insertSchedule(contractId, policyVersionId, commissionItemId, agentId, 1, "650000");
        PaymentInsert confirmed = insertPayment(
                contractId, policyVersionId, commissionItemId, agentId,
                1, "650000", "CONFIRMED", "NO-JOURNAL");

        GaFcMatchCandidate result = matcher.match(new ReconciliationExecutionRequest(
                99L, 88L, TEST_MONTH, PaymentStage.GA_TO_FC, insurerId, null)).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.MATCHED);
        assertThat(result.scheduleLineIds()).containsExactly(scheduleLineId);
        assertThat(result.transactionAttributionIds()).containsExactly(confirmed.attributionId());
        assertThat(result.expectedJournalHeaderIds()).isEmpty();
        assertThat(result.actualJournalHeaderIds()).isEmpty();
    }

    private Long insertSchedule(
            Long contractId,
            Long policyVersionId,
            Long commissionItemId,
            Long agentId,
            int installmentNo,
            String amount
    ) {
        return insertSchedule(
                contractId, policyVersionId, commissionItemId, agentId,
                installmentNo, amount, "OPERATIONAL", null, true, 300 + installmentNo);
    }

    private Long insertSchedule(
            Long contractId,
            Long policyVersionId,
            Long commissionItemId,
            Long agentId,
            int installmentNo,
            String amount,
            String schedulePurpose,
            String scenarioCode,
            boolean active,
            int scheduleVersionNo
    ) {
        Long headerId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header (
                    contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_purpose, scenario_code, schedule_regime, active_yn
                ) VALUES (?, 'GA_TO_FC', ?, ?, ?, ?, 'CURRENT', ?)
                RETURNING schedule_header_id
                """, Long.class,
                contractId, policyVersionId, scheduleVersionNo,
                schedulePurpose, scenarioCode, active);
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_line (
                    schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, beneficiary_agent_id, basis_code, basis_amount,
                    calculation_type, fixed_amount, expected_amount
                ) VALUES (?, 1, ?, ?, ?, ?, ?, 'IT_RECONCILIATION', ?, 'FIXED', ?, ?)
                RETURNING schedule_line_id
                """, Long.class,
                headerId, installmentNo, installmentNo, TEST_MONTH.plusDays(14),
                commissionItemId, agentId,
                new BigDecimal(amount), new BigDecimal(amount), new BigDecimal(amount));
    }

    private PaymentInsert insertPayment(
            Long contractId,
            Long policyVersionId,
            Long commissionItemId,
            Long agentId,
            Integer installmentNo,
            String amount,
            String status,
            String suffix
    ) {
        return insertPayment(
                contractId, policyVersionId, commissionItemId, agentId,
                installmentNo, amount, status, suffix,
                "GA_TO_FC", "GA_MANUAL_PAYMENT");
    }

    private PaymentInsert insertPayment(
            Long contractId,
            Long policyVersionId,
            Long commissionItemId,
            Long agentId,
            Integer installmentNo,
            String amount,
            String status,
            String suffix,
            String paymentStage,
            String sourceType
    ) {
        Long transactionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    payment_stage, source_type, source_business_key, source_contract_id,
                    recipient_agent_id, commission_item_id, policy_version_id, installment_no,
                    settlement_month, due_date, amount, cashflow_type, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PAYMENT', 'DRAFT')
                RETURNING commission_transaction_id
                """, Long.class,
                paymentStage, sourceType,
                "IT-048-03:" + suffix, contractId, agentId, commissionItemId, policyVersionId,
                installmentNo, TEST_MONTH, TEST_MONTH.plusDays(14), new BigDecimal(amount));
        Long attributionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id, attribution_seq, attribution_scope, contract_id,
                    attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, attribution_method
                ) VALUES (?, 1, 'CONTRACT', ?, ?, ?, ?, 'INCLUDED', 'DIRECT')
                RETURNING transaction_attribution_id
                """, Long.class,
                transactionId, contractId, TEST_MONTH.plusDays(14), TEST_MONTH, new BigDecimal(amount));
        if ("CONFIRMED".equals(status)) {
            jdbcTemplate.update("""
                    UPDATE fgc.commission_transaction
                       SET status = 'CONFIRMED'
                     WHERE commission_transaction_id = ?
                    """, transactionId);
        }
        return new PaymentInsert(transactionId, attributionId);
    }

    private Long insertPostedJournal(
            String journalType,
            String sourceEntityType,
            Long sourceEntityId,
            Long contractId,
            Long agentId,
            Long commissionItemId,
            String amount,
            String debitAccountCode,
            String creditAccountCode
    ) {
        Long journalHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header (
                    journal_no, journal_date, journal_type, source_entity_type,
                    source_entity_id, contract_id, policy_version_id, status
                ) VALUES (?, ?, ?, ?, ?, ?, NULL, 'DRAFT')
                RETURNING journal_header_id
                """, Long.class,
                "IT-048-03-" + journalType + "-" + sourceEntityId,
                TEST_MONTH.plusDays(14), journalType, sourceEntityType,
                String.valueOf(sourceEntityId), contractId);
        Long debitAccountId = id(
                "SELECT journal_account_id FROM fgc.journal_account WHERE account_code = ?", debitAccountCode);
        Long creditAccountId = id(
                "SELECT journal_account_id FROM fgc.journal_account WHERE account_code = ?", creditAccountCode);
        BigDecimal journalAmount = new BigDecimal(amount);
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line (
                    journal_header_id, line_no, journal_account_id, debit_amount, credit_amount,
                    contract_id, agent_id, payment_stage, commission_item_id
                ) VALUES (?, 1, ?, ?, 0, ?, ?, 'GA_TO_FC', ?),
                         (?, 2, ?, 0, ?, ?, ?, 'GA_TO_FC', ?)
                """,
                journalHeaderId, debitAccountId, journalAmount, contractId, agentId, commissionItemId,
                journalHeaderId, creditAccountId, journalAmount, contractId, agentId, commissionItemId);
        jdbcTemplate.update(
                "UPDATE fgc.journal_header SET status = 'POSTED' WHERE journal_header_id = ?",
                journalHeaderId);
        return journalHeaderId;
    }

    private Long id(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private record PaymentInsert(Long transactionId, Long attributionId) {
    }
}
