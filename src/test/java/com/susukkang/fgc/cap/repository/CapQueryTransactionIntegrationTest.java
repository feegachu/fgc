package com.susukkang.fgc.cap.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapContractView;
import com.susukkang.fgc.cap.dto.CapIncludedAmountSummary;
import com.susukkang.fgc.cap.dto.CapRuleSetView;
import com.susukkang.fgc.cap.dto.RefundRateTableView;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.transaction.performance.PaymentJdbcObservation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 쓰기 트랜잭션에 참여하는 한도 조회의 AUTO flush와 깨끗한 조회의 무변경을 검증한다.
 * 독립 readOnly 트랜잭션의 flush 모드를 검증하는 테스트는 아니다.
 * JDBC 관측은 현재 스레드의 클라이언트 SQL이며 DB 트리거 내부 SQL은 포함하지 않는다.
 *
 * @author hjKang
 * @since 2026-10-02
 * @version 1.0
 */
@SpringBootTest(properties = {
        "fgc.batch.daily-changed-contract.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@Import(PaymentJdbcObservation.Config.class)
@Transactional
class CapQueryTransactionIntegrationTest {
    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 7, 10);
    private static final Path REPORTS = Path.of("build/reports/cap-transactions");

    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CapContractQueryRepository contracts;
    @Autowired private CapRuleQueryRepository rules;
    @Autowired private CapScheduleAmountQueryRepository schedules;
    @Autowired private CapIncludedAmountQueryRepository includedAmounts;
    @Autowired private RefundRateQueryRepository refunds;
    @Autowired private CapCalculator calculator;

    private Long contractId;
    private Long scheduleHeaderId;
    private Long currentPaymentId;
    private RefundScope refundScope;
    private final List<Long> paymentIds = new ArrayList<>();

    @BeforeEach
    void prepareIsolatedInputs() {
        contractId = jdbc.queryForObject("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date,
                    agent_id, organization_id, premium_per_cycle_amount,
                    first_premium_amount, monthly_equivalent_first_premium,
                    premium_conversion_rule_code, payment_cycle_code, payment_term_months,
                    standard_surrender_deduction_amount, current_status, data_origin
                )
                SELECT insurer_id, product_offering_id, ?, contract_date,
                       agent_id, organization_id, premium_per_cycle_amount,
                       first_premium_amount, monthly_equivalent_first_premium,
                       premium_conversion_rule_code, payment_cycle_code, payment_term_months,
                       standard_surrender_deduction_amount, current_status, 'MANUAL'
                  FROM fgc.insurance_contract WHERE contract_no = 'FGC-FGL01-202607-0001'
                RETURNING contract_id
                """, Long.class, "IT-CAP-FLUSH-" + UUID.randomUUID());
        scheduleHeaderId = jdbc.queryForObject("""
                INSERT INTO fgc.schedule_header (
                    contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_regime, schedule_purpose, active_yn
                )
                SELECT ?, 'GA_TO_FC', policy_version_id, 1, 'CURRENT', 'OPERATIONAL', true
                  FROM fgc.policy_version WHERE policy_code = 'GA-CUR-2026-V1' AND status = 'ACTIVE'
                RETURNING schedule_header_id
                """, Long.class, contractId);
        jdbc.update("""
                INSERT INTO fgc.schedule_line (
                    schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, basis_code, basis_amount, calculation_type, rate_pct, expected_amount
                )
                SELECT ?, 1, 1, 1, DATE '2026-07-10', commission_item_id,
                       'MONTHLY_EQUIVALENT_FIRST_PREMIUM', 100000, 'RATE', 100, 100.50
                  FROM fgc.commission_item WHERE item_code = 'BASE_COMMISSION'
                """, scheduleHeaderId);
        insertPayment(CONTRACT_DATE, "100.50", true);
        insertPayment(CONTRACT_DATE.plusDays(1), "200.50", true);
        currentPaymentId = insertPayment(CONTRACT_DATE.plusDays(1), "50.40", false);
        refundScope = jdbc.queryForObject("""
                SELECT r.insurer_id, r.product_id, r.payment_term_months, r.channel_code, r.effective_from
                  FROM fgc.refund_rate_table r
                  JOIN fgc.policy_version p ON p.policy_version_id = r.policy_version_id
                 WHERE p.status = 'ACTIVE'
                   AND EXISTS (SELECT 1 FROM fgc.refund_rate_line l
                                WHERE l.refund_rate_table_id = r.refund_rate_table_id AND l.contract_month_no = 12)
                 ORDER BY r.refund_rate_table_id LIMIT 1
                """, (rs, row) -> new RefundScope(rs.getLong(1), rs.getLong(2), rs.getInt(3),
                rs.getString(4), rs.getObject(5, LocalDate.class)));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void jpqlFlushesPendingPremiumAndCalculatorReadsTheUpdatedInput() {
        InsuranceContract contract = entityManager.find(InsuranceContract.class, contractId);
        changeContract(contract, CONTRACT_DATE, new BigDecimal("200000"));
        assertThat(databasePremium()).isEqualByComparingTo("100000");

        Observed<CalculationInputs> dirty = observe("jpql-pending-premium", () -> new CalculationInputs(
                contracts.findCapViewByContractId(contractId), calculate()));

        assertThat(dirty.result().contract().getMonthlyEquivalentFirstPremium()).isEqualByComparingTo("200000");
        assertThat(dirty.result().calculation().basePremiumAmount()).isEqualByComparingTo("200000");
        assertThat(dirty.result().calculation().limitAmount()).isEqualByComparingTo("2400000");
        assertThat(dirty.result().calculation().includedAmount()).isEqualByComparingTo("101");
        assertOnlyPendingContractUpdate(dirty);
        assertThat(databasePremium()).isEqualByComparingTo("200000");

        Observed<CapCalculationResult> clean = observe("jpql-clean-calculation", this::calculate);
        assertThat(clean.result()).usingRecursiveComparison().isEqualTo(dirty.result().calculation());
        assertOnlySelects(clean);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void nativeIncludedQueryFlushesPendingContractDateBeforeApplyingItsDateRange(boolean preConfirm) {
        InsuranceContract contract = entityManager.find(InsuranceContract.class, contractId);
        changeContract(contract, CONTRACT_DATE.plusDays(1), contract.getMonthlyEquivalentFirstPremium());
        assertThat(jdbc.queryForObject("SELECT contract_date FROM fgc.insurance_contract WHERE contract_id = ?",
                LocalDate.class, contractId)).isEqualTo(CONTRACT_DATE);
        String mode = preConfirm ? "pre-confirm" : "confirmed";

        Observed<List<CapIncludedAmountSummary>> dirty = observe("native-pending-date-" + mode,
                () -> included(preConfirm));

        assertThat(dirty.result()).singleElement().satisfies(row ->
                assertThat(row.getIncludedAmount()).isEqualByComparingTo(preConfirm ? "251" : "201"));
        assertOnlyPendingContractUpdate(dirty);
        Observed<List<CapIncludedAmountSummary>> clean = observe("native-clean-" + mode,
                () -> included(preConfirm));
        assertThat(clean.result()).usingRecursiveComparison().isEqualTo(dirty.result());
        assertOnlySelects(clean);
    }

    @Test
    void nativeScheduleQueryFlushesExistingCallerChangesButDoesNotGenerateAnotherUpdate() {
        InsuranceContract contract = entityManager.find(InsuranceContract.class, contractId);
        changeContract(contract, CONTRACT_DATE, new BigDecimal("200000"));
        assertThat(databasePremium()).isEqualByComparingTo("100000");

        // EntityManager native 조회는 스케줄 외 테이블에 대기 중인 호출자 변경도 동기화할 수 있다.
        var dirty = observe("native-schedule-pending-premium",
                () -> schedules.findFirstYearScheduleAmounts(contractId, "GA_TO_FC", 12));

        assertThat(dirty.result()).singleElement().satisfies(row ->
                assertThat(row.getAmount()).isEqualByComparingTo("100.50"));
        assertOnlyPendingContractUpdate(dirty);
        assertThat(databasePremium()).isEqualByComparingTo("200000");
        var clean = observe("native-schedule-clean",
                () -> schedules.findFirstYearScheduleAmounts(contractId, "GA_TO_FC", 12));
        assertOnlySelects(clean);
    }

    @Test
    void cleanReferenceQueriesAndCalculationLoadNoEntitiesAndExecuteNoDml() {
        Observed<CapCalculationResult> first = observe("all-inputs-clean-first", this::readAllInputs);
        Observed<CapCalculationResult> repeated = observe("all-inputs-clean-repeat", this::readAllInputs);

        assertOnlySelects(first);
        assertOnlySelects(repeated);
        assertThat(repeated.result()).usingRecursiveComparison().isEqualTo(first.result());
        assertThat(first.result().basePremiumAmount()).isEqualByComparingTo("100000");
        assertThat(first.result().limitAmount()).isEqualByComparingTo("1200000");
        assertThat(first.result().includedAmount()).isEqualByComparingTo("101");
    }

    private CapCalculationResult readAllInputs() {
        CapContractView contract = contracts.findCapViewByContractId(contractId);
        CapRuleSetView rule = rules.findApplicableRuleSet("GA_TO_FC", contract.getContractDate(),
                contract.getInsurerId(), contract.getProductGroupCode(), contract.getChannelCode());
        assertThat(rules.findRuleItems(rule.getCapRuleSetId())).isNotEmpty();
        assertThat(schedules.findFirstYearScheduleAmounts(contractId, "GA_TO_FC", rule.getFirstYearMonths())).hasSize(1);
        RefundRateTableView table = refunds.findApplicableTable(refundScope.insurerId(), refundScope.productId(),
                refundScope.paymentTermMonths(), refundScope.channel(), refundScope.asOfDate());
        assertThat(table).isNotNull();
        assertThat(refunds.findRateAtMonth(table.getRefundRateTableId(), 12)).isNotNull();
        assertThat(included(true)).singleElement().satisfies(row ->
                assertThat(row.getIncludedAmount()).isEqualByComparingTo("352"));
        assertThat(included(false)).singleElement().satisfies(row ->
                assertThat(row.getIncludedAmount()).isEqualByComparingTo("302"));
        return calculate();
    }

    private List<CapIncludedAmountSummary> included(boolean preConfirm) {
        return preConfirm
                ? includedAmounts.sumIncludedAmountByContractAndAgent(contractId, currentPaymentId, PaymentStage.GA_TO_FC)
                : includedAmounts.sumConfirmedIncludedAmountByContractAndAgent(contractId, PaymentStage.GA_TO_FC);
    }

    private CapCalculationResult calculate() {
        return calculator.calculate(CapCalculationCommand.realtime(contractId, PaymentStage.GA_TO_FC, CONTRACT_DATE));
    }

    private BigDecimal databasePremium() {
        return jdbc.queryForObject("SELECT monthly_equivalent_first_premium FROM fgc.insurance_contract WHERE contract_id = ?",
                BigDecimal.class, contractId);
    }

    private void changeContract(InsuranceContract c, LocalDate date, BigDecimal premium) {
        c.updateDetails(c.getInsurerId(), c.getProductOfferingId(), c.getContractNo(), date,
                c.getAgentId(), c.getOrganizationId(), premium, premium, premium,
                c.getPremiumConversionRuleCode(), c.getPaymentCycleCode(), c.getPaymentTermMonths(),
                c.getStandardSurrenderDeductionAmount(), c.getCurrentStatus());
    }

    private Long insertPayment(LocalDate attributionDate, String amount, boolean confirmed) {
        Long paymentId = jdbc.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    payment_stage, source_type, source_business_key, recipient_agent_id,
                    commission_item_id, settlement_month, amount, cashflow_type, status
                )
                SELECT 'GA_TO_FC', 'GA_MANUAL_PAYMENT', ?, c.agent_id, i.commission_item_id,
                       DATE '2026-07-01', ?, 'PAYMENT', 'DRAFT'
                  FROM fgc.insurance_contract c CROSS JOIN fgc.commission_item i
                 WHERE c.contract_id = ? AND i.item_code = 'BASE_COMMISSION'
                RETURNING commission_transaction_id
                """, Long.class, "IT-CAP-FLUSH-" + UUID.randomUUID(), new BigDecimal(amount), contractId);
        paymentIds.add(paymentId);
        jdbc.update("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id, attribution_seq, attribution_scope, contract_id, agent_id,
                    attribution_date, attribution_month, attributed_amount, inclusion_status_snapshot,
                    attribution_method, allocation_basis_snapshot, evidence_ref
                )
                SELECT ?, 1, 'CONTRACT', contract_id, agent_id, ?, DATE '2026-07-01', ?,
                       'INCLUDED', 'DIRECT', '{}'::jsonb, 'CAP-FLUSH-TEST'
                  FROM fgc.insurance_contract WHERE contract_id = ?
                """, paymentId, attributionDate, new BigDecimal(amount), contractId);
        if (confirmed) {
            jdbc.update("UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = ?",
                    paymentId);
        }
        return paymentId;
    }

    private <T> Observed<T> observe(String name, Supplier<T> work) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        T result;
        PaymentJdbcObservation.Snapshot snapshot;
        PaymentJdbcObservation.start();
        try {
            result = work.get();
        } finally {
            snapshot = PaymentJdbcObservation.stop();
            try {
                Files.createDirectories(REPORTS);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(REPORTS.resolve(name + ".json").toFile(),
                        Map.of("scenario", name, "transaction", "existing write transaction / AUTO flush",
                                "sql_count", snapshot.executions().size(), "dml_count", dmlCount(snapshot),
                                "entity_load_count", statistics.getEntityLoadCount(), "sql", snapshot.executions()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new Observed<>(result, snapshot, statistics.getEntityLoadCount());
    }

    private static long dmlCount(PaymentJdbcObservation.Snapshot snapshot) {
        return snapshot.executions().stream().filter(sql -> normalized(sql.sql()).matches("(?s)^(insert|update|delete|merge)\\b.*")).count();
    }

    private static String normalized(String sql) {
        return sql.replaceFirst("(?s)^\\s*/\\*.*?\\*/\\s*", "").stripLeading().toLowerCase(Locale.ROOT);
    }

    private static void assertOnlyPendingContractUpdate(Observed<?> observed) {
        assertThat(dmlCount(observed.sql())).isEqualTo(1);
        assertThat(observed.sql().executions().get(0).sql()).containsIgnoringCase("update fgc.insurance_contract");
        assertThat(observed.entityLoads()).isZero();
    }

    private static void assertOnlySelects(Observed<?> observed) {
        assertThat(observed.sql().executions()).isNotEmpty().allSatisfy(sql ->
                assertThat(normalized(sql.sql())).startsWith("select"));
        assertThat(dmlCount(observed.sql())).isZero();
        assertThat(observed.entityLoads()).isZero();
    }

    @AfterTransaction
    void verifiesFixtureAndItsUpdatesWereRolledBack() {
        if (contractId == null) return;
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.insurance_contract WHERE contract_id = ?", Long.class, contractId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.schedule_header WHERE schedule_header_id = ?", Long.class, scheduleHeaderId)).isZero();
        for (Long paymentId : paymentIds) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.commission_transaction WHERE commission_transaction_id = ?", Long.class, paymentId)).isZero();
        }
    }

    private record Observed<T>(T result, PaymentJdbcObservation.Snapshot sql, long entityLoads) { }
    private record CalculationInputs(CapContractView contract, CapCalculationResult calculation) { }
    private record RefundScope(Long insurerId, Long productId, Integer paymentTermMonths, String channel, LocalDate asOfDate) { }
}
