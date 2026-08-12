package com.susukkang.fgc.reconciliation;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;
import com.susukkang.fgc.reconciliation.dto.InsurerGaMatchCandidate;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import com.susukkang.fgc.reconciliation.service.InsurerGaReconciliationMatcher;
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
 * 설명 : 실제 PostgreSQL에서 보험사→GA 대사 원천 필터와 매칭 결과를 검증
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@SpringBootTest
@Transactional
class InsurerGaReconciliationIntegrationTest {

    private static final LocalDate TEST_MONTH = LocalDate.of(2098, 8, 1);

    @Autowired
    private InsurerGaReconciliationMatcher matcher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 기존_정규화_DB에서_운영_스케줄과_승인된_원수사_명세만_매칭한다() {
        Long insurerId = id("SELECT insurer_id FROM fgc.insurer WHERE active_yn = true ORDER BY insurer_id LIMIT 1");
        Long otherInsurerId = id("SELECT insurer_id FROM fgc.insurer WHERE active_yn = true AND insurer_id <> ? ORDER BY insurer_id LIMIT 1", insurerId);
        Long contractId = id("SELECT contract_id FROM fgc.insurance_contract WHERE insurer_id = ? ORDER BY contract_id LIMIT 1", insurerId);
        Long secondContractId = id("SELECT contract_id FROM fgc.insurance_contract WHERE insurer_id = ? AND contract_id <> ? ORDER BY contract_id LIMIT 1", insurerId, contractId);
        Long otherContractId = id("SELECT contract_id FROM fgc.insurance_contract WHERE insurer_id = ? ORDER BY contract_id LIMIT 1", otherInsurerId);
        Long policyVersionId = id("SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1");
        Long commissionItemId = id("SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1");

        Long expectedMatched = insertSchedule(contractId, policyVersionId, commissionItemId, "650000", true, "OPERATIONAL", 1);
        Long expectedMissing = insertSchedule(secondContractId, policyVersionId, commissionItemId, "300000", true, "OPERATIONAL", 2);
        insertSchedule(contractId, policyVersionId, commissionItemId, "777000", false, "OPERATIONAL", 3);
        insertSchedule(contractId, policyVersionId, commissionItemId, "888000", true, "COMPARISON", 4);
        insertSchedule(otherContractId, policyVersionId, commissionItemId, "999000", true, "OPERATIONAL", 1);

        Long statementBatchId = insertStatementBatch(insurerId, TEST_MONTH, "VALIDATED", "VALID");
        Long actualMatched = insertActual(statementBatchId, insurerId, contractId, commissionItemId,
                TEST_MONTH, "650000", "MATCHED");

        Long nextMonthBatchId = insertStatementBatch(insurerId, TEST_MONTH.plusMonths(1), "VALIDATED", "NEXT-MONTH");
        Long nextMonthActual = insertActual(nextMonthBatchId, insurerId, contractId, commissionItemId,
                TEST_MONTH.plusMonths(1), "100000", "NEXT-MONTH");

        Long rejectedBatchId = insertStatementBatch(insurerId, TEST_MONTH, "REJECTED", "REJECTED");
        insertActual(rejectedBatchId, insurerId, contractId, commissionItemId,
                TEST_MONTH, "650000", "REJECTED");
        Long otherInsurerBatchId = insertStatementBatch(otherInsurerId, TEST_MONTH, "VALIDATED", "OTHER-INSURER");
        insertActual(otherInsurerBatchId, otherInsurerId, otherContractId, commissionItemId,
                TEST_MONTH, "999000", "OTHER-INSURER");
        Long agentId = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", contractId);
        Long gaToFcActual = insertGaToFcActual(
                statementBatchId, insurerId, contractId, agentId, commissionItemId, "650000");

        Long expectedJournalId = insertPostedJournal(
                "EXPECTED_INSURER_INCOME", "SCHEDULE_LINE", expectedMatched,
                contractId, commissionItemId, "650000", "EXPECTED_RECEIVABLE", "EXPECTED_INCOME");
        Long actualTransactionId = id("""
                SELECT commission_transaction_id
                  FROM fgc.transaction_attribution
                 WHERE transaction_attribution_id = ?
                """, actualMatched);
        Long actualJournalId = insertPostedJournal(
                "ACTUAL_INSURER_STATEMENT", "COMMISSION_TRANSACTION", actualTransactionId,
                contractId, commissionItemId, "650000", "ACTUAL_RECEIVABLE", "ACTUAL_INCOME");

        List<InsurerGaMatchCandidate> results = matcher.match(new ReconciliationExecutionRequest(
                99L, 88L, TEST_MONTH, PaymentStage.INSURER_TO_GA, insurerId, null));

        assertThat(results).hasSize(2);
        assertThat(results).anySatisfy(result -> {
            assertThat(result.resultType()).isEqualTo(ReconciliationResultType.MATCHED);
            assertThat(result.scheduleLineIds()).containsExactly(expectedMatched);
            assertThat(result.transactionAttributionIds()).containsExactly(actualMatched);
            assertThat(result.expectedJournalHeaderIds()).containsExactly(expectedJournalId);
            assertThat(result.actualJournalHeaderIds()).containsExactly(actualJournalId);
        });
        assertThat(results).anySatisfy(result -> {
            assertThat(result.resultType()).isEqualTo(ReconciliationResultType.ACTUAL_MISSING);
            assertThat(result.scheduleLineIds()).containsExactly(expectedMissing);
        });
        assertThat(results).flatExtracting(InsurerGaMatchCandidate::transactionAttributionIds)
                .doesNotContain(nextMonthActual, gaToFcActual);
    }

    @Test
    void 동일_키_실제_귀속행_두개를_합쳐_정상으로_숨기지_않고_DUPLICATE로_표시한다() {
        Long insurerId = id("SELECT insurer_id FROM fgc.insurer WHERE active_yn = true ORDER BY insurer_id LIMIT 1");
        Long contractId = id("SELECT contract_id FROM fgc.insurance_contract WHERE insurer_id = ? ORDER BY contract_id LIMIT 1", insurerId);
        Long policyVersionId = id("SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1");
        Long commissionItemId = id("SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1");
        Long expected = insertSchedule(contractId, policyVersionId, commissionItemId, "650000", true, "OPERATIONAL", 1);
        Long statementBatchId = insertStatementBatch(insurerId, TEST_MONTH, "AVAILABLE", "DUPLICATE");
        Long first = insertActual(statementBatchId, insurerId, contractId, commissionItemId,
                TEST_MONTH, "300000", "DUP-1");
        Long second = insertActual(statementBatchId, insurerId, contractId, commissionItemId,
                TEST_MONTH, "350000", "DUP-2");

        InsurerGaMatchCandidate result = matcher.match(new ReconciliationExecutionRequest(
                99L, 88L, TEST_MONTH, PaymentStage.INSURER_TO_GA, insurerId, null)).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.DUPLICATE);
        assertThat(result.expectedTotalAmount()).isEqualByComparingTo("650000");
        assertThat(result.actualTotalAmount()).isEqualByComparingTo("650000");
        assertThat(result.scheduleLineIds()).containsExactly(expected);
        assertThat(result.transactionAttributionIds()).containsExactly(first, second);
    }

    private Long insertSchedule(
            Long contractId,
            Long policyVersionId,
            Long commissionItemId,
            String amount,
            boolean active,
            String purpose,
            int installmentNo
    ) {
        Long headerId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header (
                    contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_purpose, scenario_code, schedule_regime, active_yn
                ) VALUES (?, 'INSURER_TO_GA', ?, ?, ?, ?, 'CURRENT', ?)
                RETURNING schedule_header_id
                """, Long.class,
                contractId,
                policyVersionId,
                purpose.equals("OPERATIONAL") ? 100 + installmentNo : 200 + installmentNo,
                purpose,
                purpose.equals("OPERATIONAL") ? null : "IT-048-02",
                active
        );
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_line (
                    schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, basis_code, basis_amount, calculation_type, fixed_amount,
                    expected_amount
                ) VALUES (?, 1, ?, ?, ?, ?, 'IT_RECONCILIATION', ?, 'FIXED', ?, ?)
                RETURNING schedule_line_id
                """, Long.class,
                headerId, installmentNo, installmentNo, TEST_MONTH.plusDays(14),
                commissionItemId, new BigDecimal(amount), new BigDecimal(amount), new BigDecimal(amount)
        );
    }

    private Long insertStatementBatch(Long insurerId, LocalDate month, String status, String suffix) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.statement_batch (
                    insurer_id, settlement_month, statement_type, external_statement_no,
                    received_on, source_ref, status
                ) VALUES (?, ?, 'INSURER_COMMISSION', ?, ?, ?, ?)
                RETURNING statement_batch_id
                """, Long.class,
                insurerId, month, "IT-048-02-" + suffix, month.plusDays(20), "IT-048-02", status
        );
    }

    private Long insertActual(
            Long statementBatchId,
            Long insurerId,
            Long contractId,
            Long commissionItemId,
            LocalDate month,
            String amount,
            String suffix
    ) {
        Long transactionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    statement_batch_id, payment_stage, source_type, source_business_key,
                    insurer_id, commission_item_id, settlement_month, due_date,
                    amount, cashflow_type, status
                ) VALUES (?, 'INSURER_TO_GA', 'INSURER_STATEMENT', ?, ?, ?, ?, ?, ?, 'PAYMENT', 'DRAFT')
                RETURNING commission_transaction_id
                """, Long.class,
                statementBatchId, "IT-048-02:" + suffix, insurerId, commissionItemId,
                month, month.plusDays(14), new BigDecimal(amount)
        );
        Long attributionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id, attribution_seq, attribution_scope, contract_id,
                    attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, attribution_method
                ) VALUES (?, 1, 'CONTRACT', ?, ?, ?, ?, 'INCLUDED', 'DIRECT')
                RETURNING transaction_attribution_id
                """, Long.class, transactionId, contractId, month.plusDays(14), month, new BigDecimal(amount));
        jdbcTemplate.update("""
                UPDATE fgc.commission_transaction
                   SET status = 'CONFIRMED'
                 WHERE commission_transaction_id = ?
                """, transactionId);
        return attributionId;
    }

    private Long insertGaToFcActual(
            Long statementBatchId,
            Long insurerId,
            Long contractId,
            Long agentId,
            Long commissionItemId,
            String amount
    ) {
        Long transactionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    statement_batch_id, payment_stage, source_type, source_business_key,
                    insurer_id, recipient_agent_id, commission_item_id, settlement_month,
                    due_date, amount, cashflow_type, status
                ) VALUES (?, 'GA_TO_FC', 'GA_CONFIRMED_PAYMENT', 'IT-048-02:GA-TO-FC',
                          ?, ?, ?, ?, ?, ?, 'PAYMENT', 'DRAFT')
                RETURNING commission_transaction_id
                """, Long.class,
                statementBatchId, insurerId, agentId, commissionItemId, TEST_MONTH,
                TEST_MONTH.plusDays(14), new BigDecimal(amount)
        );
        Long attributionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id, attribution_seq, attribution_scope, contract_id,
                    agent_id, attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, attribution_method
                ) VALUES (?, 1, 'CONTRACT', ?, ?, ?, ?, ?, 'INCLUDED', 'DIRECT')
                RETURNING transaction_attribution_id
                """, Long.class,
                transactionId, contractId, agentId, TEST_MONTH.plusDays(14), TEST_MONTH,
                new BigDecimal(amount)
        );
        jdbcTemplate.update("UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = ?",
                transactionId);
        return attributionId;
    }

    private Long insertPostedJournal(
            String journalType,
            String sourceEntityType,
            Long sourceEntityId,
            Long contractId,
            Long commissionItemId,
            String amount,
            String debitAccountCode,
            String creditAccountCode
    ) {
        Long journalHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header (
                    journal_no, journal_date, journal_type, source_entity_type,
                    source_entity_id, contract_id, status
                ) VALUES (?, ?, ?, ?, ?, ?, 'DRAFT')
                RETURNING journal_header_id
                """, Long.class,
                "IT-048-02-" + journalType + "-" + sourceEntityId,
                TEST_MONTH.plusDays(14), journalType, sourceEntityType,
                String.valueOf(sourceEntityId), contractId
        );
        Long debitAccountId = id("SELECT journal_account_id FROM fgc.journal_account WHERE account_code = ?", debitAccountCode);
        Long creditAccountId = id("SELECT journal_account_id FROM fgc.journal_account WHERE account_code = ?", creditAccountCode);
        BigDecimal journalAmount = new BigDecimal(amount);
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line (
                    journal_header_id, line_no, journal_account_id, debit_amount, credit_amount,
                    contract_id, payment_stage, commission_item_id
                ) VALUES (?, 1, ?, ?, 0, ?, 'INSURER_TO_GA', ?),
                         (?, 2, ?, 0, ?, ?, 'INSURER_TO_GA', ?)
                """,
                journalHeaderId, debitAccountId, journalAmount, contractId, commissionItemId,
                journalHeaderId, creditAccountId, journalAmount, contractId, commissionItemId
        );
        jdbcTemplate.update("UPDATE fgc.journal_header SET status = 'POSTED' WHERE journal_header_id = ?",
                journalHeaderId);
        return journalHeaderId;
    }

    private Long id(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }
}
