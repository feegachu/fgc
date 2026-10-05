package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.dto.CapIncludedAmountSummary;
import com.susukkang.fgc.common.code.PaymentStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : FGC-FUN-031 계약별 초년도 한도 산입액 집계 Repository 통합 테스트
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@SpringBootTest
@Transactional
class CapIncludedAmountQueryRepositoryIntegrationTest {

    @Autowired
    private CapIncludedAmountQueryRepository capIncludedAmountQueryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 설명 : 초년도 날짜 경계와 상세행 단위 반올림 후 합산을 검증한다
     *
     * @author hjKang
     * @since 2026-08-12
     */
    @Test
    void sumsRoundedConfirmedAmountsWithinExactFirstYearDateRange() {
        TestReference reference = testReference();
        BigDecimal amountBeforeTest = includedAmountForAgent(
                capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(
                        reference.contractId(), PaymentStage.GA_TO_FC),
                reference.agentId());
        Long contractDateTransactionId = insertTransaction(reference, new BigDecimal("100.50"));
        insertAttribution(contractDateTransactionId, reference, reference.contractDate(), new BigDecimal("100.50"));
        confirmTransaction(contractDateTransactionId);

        Long anniversaryEveTransactionId = insertTransaction(reference, new BigDecimal("100.50"));
        insertAttribution(anniversaryEveTransactionId, reference,
                reference.contractDate().plusYears(1).minusDays(1), new BigDecimal("100.50"));
        confirmTransaction(anniversaryEveTransactionId);

        Long anniversaryTransactionId = insertTransaction(reference, new BigDecimal("999.00"));
        insertAttribution(anniversaryTransactionId, reference,
                reference.contractDate().plusYears(1), new BigDecimal("999.00"));
        confirmTransaction(anniversaryTransactionId);

        List<CapIncludedAmountSummary> summaries =
                capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(
                        reference.contractId(), PaymentStage.GA_TO_FC);

        assertThat(includedAmountForAgent(summaries, reference.agentId()))
                .isEqualByComparingTo(amountBeforeTest.add(new BigDecimal("202")));
    }

    /**
     * 설명 : 지급 확정 직전 기존 확정액과 현재 DRAFT 지급 건만 합산하는지 검증한다
     *
     * @author hjKang
     * @since 2026-08-12
     */
    @Test
    void sumsConfirmedAndCurrentDraftButExcludesOtherDraftBeforeConfirmation() {
        TestReference reference = testReference();
        BigDecimal amountBeforeTest = includedAmountForAgent(
                capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(
                        reference.contractId(), PaymentStage.GA_TO_FC),
                reference.agentId());
        Long confirmedTransactionId = insertTransaction(reference, new BigDecimal("100.50"));
        insertAttribution(confirmedTransactionId, reference, reference.contractDate(), new BigDecimal("100.50"));
        confirmTransaction(confirmedTransactionId);

        Long currentDraftTransactionId = insertTransaction(reference, new BigDecimal("50.40"));
        insertAttribution(currentDraftTransactionId, reference,
                reference.contractDate().plusMonths(1), new BigDecimal("50.40"));

        Long otherDraftTransactionId = insertTransaction(reference, new BigDecimal("800.00"));
        insertAttribution(otherDraftTransactionId, reference,
                reference.contractDate().plusMonths(2), new BigDecimal("800.00"));

        List<CapIncludedAmountSummary> preConfirmSummaries =
                capIncludedAmountQueryRepository.sumIncludedAmountByContractAndAgent(
                        reference.contractId(), currentDraftTransactionId, PaymentStage.GA_TO_FC);
        List<CapIncludedAmountSummary> monthlySummaries =
                capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(
                        reference.contractId(), PaymentStage.GA_TO_FC);

        assertThat(includedAmountForAgent(preConfirmSummaries, reference.agentId()))
                .isEqualByComparingTo(amountBeforeTest.add(new BigDecimal("151")));
        assertThat(includedAmountForAgent(monthlySummaries, reference.agentId()))
                .isEqualByComparingTo(amountBeforeTest.add(new BigDecimal("101")));
    }

    /**
     * 설명 : 동일 계약과 설계사의 산입액을 지급단계별로 분리하여 합산하는지 검증한다
     *
     * @author hjKang
     * @since 2026-08-12
     */
    @Test
    void separatesIncludedAmountsByPaymentStage() {
        TestReference reference = testReference();
        BigDecimal gaToFcAmountBeforeTest = includedAmountForAgent(
                capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(
                        reference.contractId(), PaymentStage.GA_TO_FC),
                reference.agentId());
        BigDecimal insurerToGaAmountBeforeTest = includedAmountForAgent(
                capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(
                        reference.contractId(), PaymentStage.INSURER_TO_GA),
                reference.agentId());

        Long gaToFcTransactionId = insertTransaction(
                reference, PaymentStage.GA_TO_FC, new BigDecimal("100.00"));
        insertAttribution(gaToFcTransactionId, reference,
                reference.contractDate(), new BigDecimal("100.00"));
        confirmTransaction(gaToFcTransactionId);

        Long insurerToGaTransactionId = insertTransaction(
                reference, PaymentStage.INSURER_TO_GA, new BigDecimal("500000.00"));
        insertAttribution(insurerToGaTransactionId, reference,
                reference.contractDate(), new BigDecimal("500000.00"));
        confirmTransaction(insurerToGaTransactionId);

        List<CapIncludedAmountSummary> gaToFcSummaries =
                capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(
                        reference.contractId(), PaymentStage.GA_TO_FC);
        List<CapIncludedAmountSummary> insurerToGaSummaries =
                capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(
                        reference.contractId(), PaymentStage.INSURER_TO_GA);

        assertThat(includedAmountForAgent(gaToFcSummaries, reference.agentId()))
                .isEqualByComparingTo(gaToFcAmountBeforeTest.add(new BigDecimal("100")));
        assertThat(includedAmountForAgent(insurerToGaSummaries, reference.agentId()))
                .isEqualByComparingTo(insurerToGaAmountBeforeTest.add(new BigDecimal("500000")));
    }

    /**
     * 설명 : 월 재합산 시 확정된 차감 지급 건을 상세행 반올림 후 산입액에서 차감하는지 검증한다
     *
     * @author hjKang
     * @since 2026-08-12
     */
    @Test
    void subtractsConfirmedDeductionFromMonthlyIncludedAmount() {
        TestReference reference = testReference();
        BigDecimal amountBeforeTest = includedAmountForAgent(
                capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(
                        reference.contractId(), PaymentStage.GA_TO_FC),
                reference.agentId());

        Long paymentTransactionId = insertTransaction(
                reference, PaymentStage.GA_TO_FC, "PAYMENT", new BigDecimal("1000.50"));
        insertAttribution(paymentTransactionId, reference,
                reference.contractDate(), new BigDecimal("1000.50"));
        confirmTransaction(paymentTransactionId);

        Long deductionTransactionId = insertTransaction(
                reference, PaymentStage.GA_TO_FC, "DEDUCTION", new BigDecimal("200.50"));
        insertAttribution(deductionTransactionId, reference,
                reference.contractDate().plusMonths(1), new BigDecimal("200.50"));
        confirmTransaction(deductionTransactionId);

        List<CapIncludedAmountSummary> summaries =
                capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(
                        reference.contractId(), PaymentStage.GA_TO_FC);

        assertThat(includedAmountForAgent(summaries, reference.agentId()))
                .isEqualByComparingTo(amountBeforeTest.add(new BigDecimal("800")));
    }

    /**
     * 설명 : 지급 확정 직전 현재 DRAFT 차감 지급 건을 산입액에서 차감하는지 검증한다
     *
     * @author hjKang
     * @since 2026-08-12
     */
    @Test
    void subtractsCurrentDraftDeductionBeforeConfirmation() {
        TestReference reference = testReference();
        BigDecimal amountBeforeTest = includedAmountForAgent(
                capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(
                        reference.contractId(), PaymentStage.GA_TO_FC),
                reference.agentId());

        Long paymentTransactionId = insertTransaction(
                reference, PaymentStage.GA_TO_FC, "PAYMENT", new BigDecimal("1000.50"));
        insertAttribution(paymentTransactionId, reference,
                reference.contractDate(), new BigDecimal("1000.50"));
        confirmTransaction(paymentTransactionId);

        Long currentDeductionTransactionId = insertTransaction(
                reference, PaymentStage.GA_TO_FC, "DEDUCTION", new BigDecimal("200.50"));
        insertAttribution(currentDeductionTransactionId, reference,
                reference.contractDate().plusMonths(1), new BigDecimal("200.50"));

        List<CapIncludedAmountSummary> summaries =
                capIncludedAmountQueryRepository.sumIncludedAmountByContractAndAgent(
                        reference.contractId(), currentDeductionTransactionId, PaymentStage.GA_TO_FC);

        assertThat(includedAmountForAgent(summaries, reference.agentId()))
                .isEqualByComparingTo(amountBeforeTest.add(new BigDecimal("800")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void usesCalendarYearForLeapDayContractAndExcludesDatesBeforeContract(boolean preConfirm) {
        TestReference reference = insertContract(LocalDate.of(2032, 2, 29));
        insertConfirmedAttribution(reference, LocalDate.of(2032, 2, 28), "999.00");
        Long currentId = insertConfirmedAttribution(reference, LocalDate.of(2032, 2, 29), "100.50");
        insertConfirmedAttribution(reference, LocalDate.of(2033, 2, 27), "100.50");
        insertConfirmedAttribution(reference, LocalDate.of(2033, 2, 28), "999.00");

        assertThat(includedAmountForAgent(summaries(reference, currentId, preConfirm), reference.agentId()))
                .isEqualByComparingTo("202");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void excludesNonincludedAttributionsAndCancelledTransactions(boolean preConfirm) {
        TestReference reference = insertContract(LocalDate.of(2032, 1, 15));
        insertConfirmedAttribution(reference, reference.contractDate(), "100.50");
        Long excludedId = insertTransaction(reference, new BigDecimal("800.00"));
        insertAttribution(excludedId, reference, reference.contractDate(), new BigDecimal("800.00"), "EXCLUDED");
        confirmTransaction(excludedId);
        Long reviewId = insertTransaction(reference, new BigDecimal("900.00"));
        insertAttribution(reviewId, reference, reference.contractDate(), new BigDecimal("900.00"), "REVIEW_REQUIRED");
        Long cancelledId = insertConfirmedAttribution(reference, reference.contractDate(), "700.00");
        jdbcTemplate.update("UPDATE fgc.commission_transaction SET status = 'CANCELLED' WHERE commission_transaction_id = ?",
                cancelledId);

        assertThat(includedAmountForAgent(summaries(reference, reviewId, preConfirm), reference.agentId()))
                .isEqualByComparingTo("101");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mapsAgentGroupsIncludingNullAndCountsCurrentConfirmedTransactionOnlyOnce(boolean preConfirm) {
        TestReference reference = insertContract(LocalDate.of(2032, 1, 15));
        Long otherAgentId = jdbcTemplate.queryForObject(
                "SELECT min(agent_id) FROM fgc.agent WHERE agent_id <> ?", Long.class, reference.agentId());
        Long[] agentIds = {reference.agentId(), otherAgentId, null};
        Long currentId = null;
        for (int i = 0; i < agentIds.length; i++) {
            BigDecimal amount = BigDecimal.valueOf((i + 1) * 100).add(new BigDecimal("0.50"));
            currentId = insertTransaction(reference, amount);
            TestReference attributed = new TestReference(reference.contractId(), reference.contractDate(),
                    agentIds[i], reference.commissionItemId());
            insertAttribution(currentId, attributed, reference.contractDate(), amount);
            confirmTransaction(currentId);
        }

        List<CapIncludedAmountSummary> actual = summaries(reference, currentId, preConfirm);

        assertThat(actual).extracting(CapIncludedAmountSummary::getAgentId)
                .containsExactlyInAnyOrder(reference.agentId(), otherAgentId, (Long) null);
        assertThat(actual).allSatisfy(summary -> {
            assertThat(summary.getContractId()).isEqualTo(reference.contractId());
            assertThat(summary.getPaymentStage()).isEqualTo(PaymentStage.GA_TO_FC);
        });
        assertThat(includedAmountForAgent(actual, reference.agentId())).isEqualByComparingTo("101");
        assertThat(includedAmountForAgent(actual, otherAgentId)).isEqualByComparingTo("201");
        assertThat(includedAmountForAgent(actual, null)).isEqualByComparingTo("301");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void excludesOtherContractsEvenWhenTheirTransactionIsCurrent(boolean preConfirm) {
        TestReference reference = insertContract(LocalDate.of(2032, 1, 15));
        TestReference otherContract = insertContract(reference.contractDate());
        insertConfirmedAttribution(reference, reference.contractDate(), "100.50");
        insertConfirmedAttribution(otherContract, otherContract.contractDate(), "800.00");
        Long currentId = insertTransaction(otherContract, new BigDecimal("900.00"));
        insertAttribution(currentId, otherContract, otherContract.contractDate(), new BigDecimal("900.00"));

        assertThat(includedAmountForAgent(summaries(reference, currentId, preConfirm), reference.agentId()))
                .isEqualByComparingTo("101");
    }

    @Test
    void returnsEmptyForMissingContractsAndContractsWithoutAttributions() {
        TestReference reference = insertContract(LocalDate.of(2032, 1, 15));

        for (Long contractId : List.of(-1L, reference.contractId())) {
            assertThat(capIncludedAmountQueryRepository.sumIncludedAmountByContractAndAgent(
                    contractId, -1L, PaymentStage.GA_TO_FC)).isEmpty();
            assertThat(capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(
                    contractId, PaymentStage.GA_TO_FC)).isEmpty();
        }
    }

    @Test
    void preservesNullLookupParametersAndUsesOnlyConfirmedAmountsWhenCurrentIdIsNull() {
        TestReference reference = insertContract(LocalDate.of(2032, 1, 15));
        Long confirmedId = insertConfirmedAttribution(reference, reference.contractDate(), "100.50");
        Long draftId = insertTransaction(reference, new BigDecimal("900.00"));
        insertAttribution(draftId, reference, reference.contractDate(), new BigDecimal("900.00"));

        assertThat(capIncludedAmountQueryRepository.sumIncludedAmountByContractAndAgent(
                null, confirmedId, PaymentStage.GA_TO_FC)).isEmpty();
        assertThat(capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(
                null, PaymentStage.GA_TO_FC)).isEmpty();
        assertThat(capIncludedAmountQueryRepository.sumIncludedAmountByContractAndAgent(
                reference.contractId(), confirmedId, null)).isEmpty();
        assertThat(capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(
                reference.contractId(), null)).isEmpty();
        assertThat(includedAmountForAgent(capIncludedAmountQueryRepository.sumIncludedAmountByContractAndAgent(
                reference.contractId(), null, PaymentStage.GA_TO_FC), reference.agentId()))
                .isEqualByComparingTo("101");
    }

    private List<CapIncludedAmountSummary> summaries(TestReference reference, Long currentId, boolean preConfirm) {
        return preConfirm
                ? capIncludedAmountQueryRepository.sumIncludedAmountByContractAndAgent(
                        reference.contractId(), currentId, PaymentStage.GA_TO_FC)
                : capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(
                        reference.contractId(), PaymentStage.GA_TO_FC);
    }

    private Long insertConfirmedAttribution(TestReference reference, LocalDate attributionDate, String amount) {
        Long transactionId = insertTransaction(reference, new BigDecimal(amount));
        insertAttribution(transactionId, reference, attributionDate, new BigDecimal(amount));
        confirmTransaction(transactionId);
        return transactionId;
    }

    private TestReference insertContract(LocalDate contractDate) {
        TestReference source = testReference();
        Long contractId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date,
                    agent_id, organization_id, premium_per_cycle_amount,
                    first_premium_amount, monthly_equivalent_first_premium,
                    premium_conversion_rule_code, payment_cycle_code,
                    payment_term_months, current_status, data_origin
                )
                SELECT insurer_id, product_offering_id, ?, ?,
                       agent_id, organization_id, premium_per_cycle_amount,
                       first_premium_amount, monthly_equivalent_first_premium,
                       premium_conversion_rule_code, payment_cycle_code,
                       payment_term_months, 'ACTIVE', 'MANUAL'
                  FROM fgc.insurance_contract WHERE contract_id = ?
                RETURNING contract_id
                """, Long.class, "IT-FUN031-" + UUID.randomUUID(), contractDate, source.contractId());
        return new TestReference(contractId, contractDate, source.agentId(), source.commissionItemId());
    }

    private BigDecimal includedAmountForAgent(List<CapIncludedAmountSummary> summaries, Long agentId) {
        return summaries.stream()
                .filter(summary -> Objects.equals(agentId, summary.getAgentId()))
                .map(CapIncludedAmountSummary::getIncludedAmount)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    private TestReference testReference() {
        return jdbcTemplate.queryForObject("""
                SELECT c.contract_id, c.contract_date, a.agent_id, ci.commission_item_id
                  FROM fgc.insurance_contract c
                  CROSS JOIN LATERAL (SELECT agent_id FROM fgc.agent ORDER BY agent_id LIMIT 1) a
                  CROSS JOIN LATERAL (SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1) ci
                 ORDER BY c.contract_id
                 LIMIT 1
                """, (resultSet, rowNum) -> new TestReference(
                resultSet.getLong("contract_id"),
                resultSet.getObject("contract_date", LocalDate.class),
                resultSet.getLong("agent_id"),
                resultSet.getLong("commission_item_id")));
    }

    private Long insertTransaction(TestReference reference, BigDecimal amount) {
        return insertTransaction(reference, PaymentStage.GA_TO_FC, amount);
    }

    private Long insertTransaction(TestReference reference, PaymentStage paymentStage, BigDecimal amount) {
        return insertTransaction(reference, paymentStage, "PAYMENT", amount);
    }

    private Long insertTransaction(TestReference reference, PaymentStage paymentStage,
                                   String cashflowType, BigDecimal amount) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    payment_stage, source_type, source_business_key, recipient_agent_id,
                    commission_item_id, settlement_month, amount, cashflow_type, status
                ) VALUES (
                    ?, 'GA_MANUAL_PAYMENT', ?, ?, ?,
                    date_trunc('month', ?::date)::date, ?, ?, 'DRAFT'
                )
                RETURNING commission_transaction_id
                """, Long.class, paymentStage.name(), "IT-FUN031-" + UUID.randomUUID(), reference.agentId(),
                reference.commissionItemId(), reference.contractDate(), amount, cashflowType);
    }

    private void insertAttribution(Long transactionId, TestReference reference,
                                   LocalDate attributionDate, BigDecimal amount) {
        insertAttribution(transactionId, reference, attributionDate, amount, "INCLUDED");
    }

    private void insertAttribution(Long transactionId, TestReference reference,
                                   LocalDate attributionDate, BigDecimal amount, String inclusionStatus) {
        jdbcTemplate.update("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id, attribution_seq, attribution_scope,
                    contract_id, agent_id, attribution_date, attribution_month,
                    attributed_amount, inclusion_status_snapshot, exclusion_type_snapshot, attribution_method,
                    allocation_basis_snapshot, evidence_ref
                ) VALUES (
                    ?, 1, 'CONTRACT', ?, ?, ?,
                    date_trunc('month', ?::date)::date, ?, ?, ?, 'DIRECT',
                    '{}'::jsonb, 'IT-FUN031'
                )
                """, transactionId, reference.contractId(), reference.agentId(), attributionDate,
                attributionDate, amount, inclusionStatus, inclusionStatus.equals("EXCLUDED") ? "NEW_AGENT_SUPPORT" : null);
    }

    private void confirmTransaction(Long transactionId) {
        jdbcTemplate.update("""
                UPDATE fgc.commission_transaction
                   SET status = 'CONFIRMED'
                 WHERE commission_transaction_id = ?
                """, transactionId);
    }

    private record TestReference(
            Long contractId,
            LocalDate contractDate,
            Long agentId,
            Long commissionItemId
    ) {
    }
}
