package com.susukkang.fgc.arbitrage.mapper;

import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckSearchCondition;
import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckSummary;
import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckView;
import com.susukkang.fgc.common.code.ArbitrageCheckStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 차익거래 검증 결과 조회 Mapper의 검색, 요약 및 페이징을 검증한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@SpringBootTest
@Transactional
class ArbitrageMapperIntegrationTest {
    private static final YearMonth TEST_MONTH = YearMonth.of(2098, 11);

    @Autowired
    private ArbitrageMapper arbitrageMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 설명 : 정산월과 지급단계 조건으로 목록과 판정별 요약 건수를 조회한다.
     *
     * @author hjKang
     * @since 2026-08-12
     */
    @Test
    void selectsItemsAndSummaryByMonthAndPaymentStage() {
        TestReference reference = testReference();
        Long validationRunId = insertValidationRun();
        insertArbitrageCheck(validationRunId, reference.contractId(), LocalDate.of(2098, 11, 1),
                PaymentStage.GA_TO_FC, ArbitrageCheckStatus.CLEAR, "이상 없음");
        insertArbitrageCheck(validationRunId, reference.contractId(), LocalDate.of(2098, 11, 2),
                PaymentStage.GA_TO_FC, ArbitrageCheckStatus.CANDIDATE, "검토 필요");
        insertArbitrageCheck(validationRunId, reference.contractId(), LocalDate.of(2098, 11, 3),
                PaymentStage.GA_TO_FC, ArbitrageCheckStatus.REVIEW_REQUIRED, "자료 부족");
        insertArbitrageCheck(validationRunId, reference.contractId(), LocalDate.of(2098, 11, 4),
                PaymentStage.INSURER_TO_GA, ArbitrageCheckStatus.CANDIDATE, "다른 지급단계");

        ArbitrageCheckSearchCondition condition = condition(
                TEST_MONTH, null, PaymentStage.GA_TO_FC, reference.insurerId());

        List<ArbitrageCheckView> items = arbitrageMapper.selectByCondition(condition, 0, 20);
        ArbitrageCheckSummary summary = arbitrageMapper.arbitrageCheckSummary(condition);

        assertThat(items).hasSize(3);
        assertThat(items).extracting(ArbitrageCheckView::getPaymentStage)
                .containsOnly(PaymentStage.GA_TO_FC);
        assertThat(items.get(1).getDecisionReason()).isEqualTo("검토 필요");
        assertThat(summary.getClearCount()).isEqualTo(1);
        assertThat(summary.getCandidateCount()).isEqualTo(1);
        assertThat(summary.getReviewRequiredCount()).isEqualTo(1);
        assertThat(summary.getTotalArbitrageChecks()).isEqualTo(3);
    }

    /**
     * 설명 : 판정 조건과 offset, size가 목록 및 요약 조회에 반영되는지 검증한다.
     *
     * @author hjKang
     * @since 2026-08-12
     */
    @Test
    void filtersByStatusAndAppliesPagination() {
        TestReference reference = testReference();
        Long validationRunId = insertValidationRun();
        insertArbitrageCheck(validationRunId, reference.contractId(), LocalDate.of(2098, 11, 10),
                PaymentStage.GA_TO_FC, ArbitrageCheckStatus.CANDIDATE, "첫 번째 후보");
        insertArbitrageCheck(validationRunId, reference.contractId(), LocalDate.of(2098, 11, 11),
                PaymentStage.GA_TO_FC, ArbitrageCheckStatus.CANDIDATE, "두 번째 후보");
        insertArbitrageCheck(validationRunId, reference.contractId(), LocalDate.of(2098, 11, 12),
                PaymentStage.GA_TO_FC, ArbitrageCheckStatus.CLEAR, "정상");

        ArbitrageCheckSearchCondition condition = condition(
                TEST_MONTH, ArbitrageCheckStatus.CANDIDATE,
                PaymentStage.GA_TO_FC, reference.insurerId());

        List<ArbitrageCheckView> firstPage = arbitrageMapper.selectByCondition(condition, 0, 1);
        List<ArbitrageCheckView> secondPage = arbitrageMapper.selectByCondition(condition, 1, 1);
        ArbitrageCheckSummary summary = arbitrageMapper.arbitrageCheckSummary(condition);

        assertThat(firstPage).singleElement()
                .extracting(ArbitrageCheckView::getDecisionReason)
                .isEqualTo("두 번째 후보");
        assertThat(secondPage).singleElement()
                .extracting(ArbitrageCheckView::getDecisionReason)
                .isEqualTo("첫 번째 후보");
        assertThat(summary.getCandidateCount()).isEqualTo(2);
        assertThat(summary.getClearCount()).isZero();
        assertThat(summary.getReviewRequiredCount()).isZero();
    }

    private ArbitrageCheckSearchCondition condition(
            YearMonth month,
            ArbitrageCheckStatus status,
            PaymentStage stage,
            Long insurerId) {
        return new ArbitrageCheckSearchCondition(month, status, stage, insurerId);
    }

    private TestReference testReference() {
        return jdbcTemplate.queryForObject("""
                SELECT c.contract_id, c.insurer_id
                  FROM fgc.insurance_contract c
                 ORDER BY c.contract_id
                 LIMIT 1
                """, (resultSet, rowNum) -> new TestReference(
                resultSet.getLong("contract_id"),
                resultSet.getLong("insurer_id")));
    }

    private Long insertValidationRun() {
        Integer nextRunNo = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(run_no), 0) + 1
                  FROM fgc.validation_run
                 WHERE validation_month = ?
                """, Integer.class, TEST_MONTH.atDay(1));

        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (
                    validation_month, run_no, run_type, status
                ) VALUES (?, ?, 'PRE_CONFIRM', 'CREATED')
                RETURNING validation_run_id
                """, Long.class, TEST_MONTH.atDay(1), nextRunNo);
    }

    private void insertArbitrageCheck(
            Long validationRunId,
            Long contractId,
            LocalDate asOfDate,
            PaymentStage paymentStage,
            ArbitrageCheckStatus status,
            String decisionReason) {
        jdbcTemplate.update("""
                INSERT INTO fgc.arbitrage_check (
                    validation_run_id, contract_id, payment_stage, as_of_date,
                    contract_month_no, cumulative_paid_premium,
                    paid_commission_amount, planned_commission_amount,
                    included_surrender_value_amount, refund_addition_applied_yn,
                    surrender_value_source_type, net_difference_amount,
                    standard_deduction_80_yn, result_status, calculation_snapshot
                ) VALUES (
                    ?, ?, ?, ?, 1, ?, ?, ?, 0, false,
                    'NOT_APPLICABLE', ?, false, ?,
                    jsonb_build_object('decisionReason', ?::text)
                )
                """,
                validationRunId,
                contractId,
                paymentStage.name(),
                asOfDate,
                new BigDecimal("100000.00"),
                new BigDecimal("500000.00"),
                new BigDecimal("100000.00"),
                new BigDecimal("500000.00"),
                status.name(),
                decisionReason
        );
    }

    private record TestReference(Long contractId, Long insurerId) {
    }
}
