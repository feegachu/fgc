package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.arbitrage.repository.ArbitrageCheckRepository;
import com.susukkang.fgc.arbitrage.service.ArbitrageService;
import com.susukkang.fgc.contract.repository.ContractFinancialSnapshotRepository;
import jakarta.persistence.EntityManager;
import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.PaymentCycleCode;
import com.susukkang.fgc.contract.dto.ContractCreateRequest;
import com.susukkang.fgc.contract.dto.ContractUpdateRequest;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.contract.repository.InsuranceContractRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 PostgreSQL에서 계약 정정과 차익거래 원천 조회를 함께 검증한다. */
@SpringBootTest(properties = "fgc.batch.daily-changed-contract.enabled=false")
@Transactional
class ContractFinancialSnapshotCorrectionIntegrationTest {
    @Autowired private ContractService contracts;
    @Autowired private InsuranceContractRepository contractRepository;
    @Autowired private ArbitrageCheckRepository arbitrage;
    @Autowired private ArbitrageService arbitrageService;
    @Autowired private ContractFinancialSnapshotRepository snapshots;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void sameDatePremiumCorrectionAppendsHistoryAndChangesCalculationSource() {
        Long id = newContract();
        InsuranceContract original = contractRepository.findById(id).orElseThrow();
        Long initialId = jdbc.queryForObject("SELECT contract_financial_snapshot_id FROM fgc.contract_financial_snapshot WHERE contract_id=?", Long.class, id);

        contracts.updateContract(id, update(original, "150000"));

        assertThat(arbitrage.selectCalculationSource(id, original.getContractDate()).getCumulativePaidPremium())
                .isEqualByComparingTo("150000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.contract_financial_snapshot WHERE contract_id=?", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT cumulative_paid_premium FROM fgc.contract_financial_snapshot WHERE contract_financial_snapshot_id=?", BigDecimal.class, initialId))
                .isEqualByComparingTo("100000");
        var selected = arbitrage.selectCalculationSource(id, original.getContractDate());
        var stored = snapshots.findById(selected.getFinancialSnapshotId()).orElseThrow();
        assertThat(stored.getSnapshotVersion()).isEqualTo(2);
        assertThat(stored.getPreviousSnapshotId()).isEqualTo(initialId);
        assertThat(selected.getFinancialSnapshotVersion()).isEqualTo(2);
        assertThat(selected.getPreviousFinancialSnapshotId()).isEqualTo(initialId);
    }

    @Test
    void successiveCorrectionsPreserveVersionsAndSameContentIsIdempotent() {
        Long id = newContract();
        correct(id, "150000", null);
        correct(id, "150000.00", null);
        ContractUpdateRequest rename = update(latest(id), "150000");
        rename.setContractNo("RENAMED-" + UUID.randomUUID());
        contracts.updateContract(id, rename);
        assertThat(snapshots.appendManualInitialIfChanged(id)).isZero();
        assertThat(snapshots.insertInitialIfAbsent(id)).isZero();
        correct(id, "175000", null);
        assertThat(jdbc.queryForList("SELECT snapshot_version FROM fgc.contract_financial_snapshot WHERE contract_id=? ORDER BY snapshot_version", Integer.class, id))
                .containsExactly(1, 2, 3);
        assertThat(jdbc.queryForList("SELECT cumulative_paid_premium FROM fgc.contract_financial_snapshot WHERE contract_id=? ORDER BY snapshot_version", BigDecimal.class, id))
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("100000"), new BigDecimal("150000"), new BigDecimal("175000"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fgc.contract_financial_snapshot current
                JOIN fgc.contract_financial_snapshot previous ON previous.contract_financial_snapshot_id=current.previous_snapshot_id
                WHERE current.contract_id=? AND current.snapshot_version=previous.snapshot_version+1
                """, Integer.class, id)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEED", "NORMALIZED_DB"})
    void nonManualInitialSourcesAreNeverCorrected(String origin) {
        Long id = newContract();
        jdbc.update("UPDATE fgc.contract_financial_snapshot SET source_ref=? WHERE contract_id=?", origin, id);
        correct(id, "150000", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.contract_financial_snapshot WHERE contract_id=?", Integer.class, id)).isEqualTo(1);
        var selected = arbitrage.selectCalculationSource(id, latest(id).getContractDate());
        assertThat(selected.getCumulativePaidPremium()).isEqualByComparingTo("100000");
        assertThat(selected.getFinancialSnapshotSourceRef()).isEqualTo(origin);
        assertThat(latest(id).getFirstPremiumAmount()).isEqualByComparingTo("150000");
    }

    @Test
    void actualWinsAtTheSameDateAfterManualCorrection() {
        Long id = newContract();
        LocalDate date = latest(id).getContractDate();
        insertExternal(id, date, "ACTUAL", "777000", 1);
        correct(id, "150000", null);
        var selected = arbitrage.selectCalculationSource(id, date);
        assertThat(selected.getSurrenderValueType()).isEqualTo("ACTUAL");
        assertThat(selected.getCumulativePaidPremium()).isEqualByComparingTo("777000");
        assertThat(selected.getFinancialSnapshotVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.contract_financial_snapshot WHERE contract_id=?", Integer.class, id)).isEqualTo(3);
    }

    @Test
    void normalizedEstimatedDataDoesNotLosePriorityToANewerSyntheticCorrectionId() {
        Long id = newContract();
        LocalDate date = latest(id).getContractDate();
        insertExternal(id, date, "ESTIMATED", "777000", 1);
        assertThat(arbitrage.selectCalculationSource(id, date).getCumulativePaidPremium()).isEqualByComparingTo("777000");
        correct(id, "150000", null);
        var selected = arbitrage.selectCalculationSource(id, date);
        assertThat(selected.getSurrenderValueType()).isEqualTo("ESTIMATED");
        assertThat(selected.getCumulativePaidPremium()).isEqualByComparingTo("777000");
        assertThat(selected.getFinancialSnapshotSourceRef()).isEqualTo("NORMALIZED_DB");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTUAL", "EXPECTED_TABLE"})
    void laterMonthlySourcesKeepDatePriorityAndTheirOriginalAmounts(String type) {
        Long id = newContract();
        LocalDate date = latest(id).getContractDate();
        LocalDate later = date.plusMonths(1);
        insertExternal(id, later, type, "777000", 2);
        correct(id, "150000", null);
        assertThat(arbitrage.selectCalculationSource(id, date).getCumulativePaidPremium()).isEqualByComparingTo("150000");
        assertThat(arbitrage.selectCalculationSource(id, later).getCumulativePaidPremium()).isEqualByComparingTo("777000");
        assertThat(arbitrage.selectCalculationSource(id, date.minusDays(1)).getFinancialSnapshotId()).isNull();
        assertThat(jdbc.queryForObject("SELECT cumulative_paid_premium FROM fgc.contract_financial_snapshot WHERE contract_id=? AND as_of_date=?", BigDecimal.class, id, later))
                .isEqualByComparingTo("777000");
    }

    @ParameterizedTest
    @ValueSource(ints = {-10, 10})
    void dateCorrectionsRetainOldRowsButUseOnlyTheCurrentSyntheticInitialDate(int days) {
        Long id = newContract();
        LocalDate original = latest(id).getContractDate();
        LocalDate corrected = original.plusDays(days);
        correct(id, "150000", corrected);
        var source = arbitrage.selectCalculationSource(id, original.plusMonths(2));
        assertThat(source.getSnapshotAsOfDate()).isEqualTo(corrected);
        assertThat(source.getCumulativePaidPremium()).isEqualByComparingTo("150000");
        assertThat(jdbc.queryForObject("SELECT cumulative_paid_premium FROM fgc.contract_financial_snapshot WHERE contract_id=? AND as_of_date=?", BigDecimal.class, id, original))
                .isEqualByComparingTo("100000");
        if (days > 0) assertThat(arbitrage.selectCalculationSource(id, original).getFinancialSnapshotId()).isNull();
        correct(id, "200000", original);
        assertThat(arbitrage.selectCalculationSource(id, original.plusMonths(2)).getCumulativePaidPremium()).isEqualByComparingTo("200000");
        assertThat(jdbc.queryForList("SELECT snapshot_version FROM fgc.contract_financial_snapshot WHERE contract_id=? AND as_of_date=? ORDER BY snapshot_version", Integer.class, id, original))
                .containsExactly(1, 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.contract_financial_snapshot WHERE contract_id=?", Integer.class, id)).isEqualTo(3);
    }

    @Test
    void missingInitialIsBackfilledWithoutReplacingActualData() {
        Long id = newContract();
        LocalDate date = latest(id).getContractDate();
        jdbc.update("DELETE FROM fgc.contract_financial_snapshot WHERE contract_id=?", id);
        insertExternal(id, date, "ACTUAL", "777000", 1);
        correct(id, "100000", null);
        correct(id, "100000", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.contract_financial_snapshot WHERE contract_id=?", Integer.class, id)).isEqualTo(2);
        assertThat(arbitrage.selectCalculationSource(id, date).getCumulativePaidPremium()).isEqualByComparingTo("777000");
    }

    @Test
    void revalidationUsesNewVersionWhileEarlierRunKeepsItsSnapshotReference() {
        Long id = newContract();
        LocalDate date = latest(id).getContractDate();
        var before = arbitrageService.checkInExistingRun(insertRun(), id, date);
        String beforeJson = jdbc.queryForObject("SELECT calculation_snapshot::text FROM fgc.arbitrage_check WHERE arbitrage_check_id=?", String.class, before.getArbitrageCheckId());
        Long initial = arbitrage.selectCalculationSource(id, date).getFinancialSnapshotId();
        correct(id, "150000", null);
        var after = arbitrageService.checkInExistingRun(insertRun(), id, date);
        assertThat(after.getCumulativePaidPremium()).isEqualByComparingTo("150000");
        assertThat(jdbc.queryForObject("SELECT (calculation_snapshot->>'financialSnapshotVersion')::integer FROM fgc.arbitrage_check WHERE arbitrage_check_id=?", Integer.class, after.getArbitrageCheckId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT (calculation_snapshot->>'previousFinancialSnapshotId')::bigint FROM fgc.arbitrage_check WHERE arbitrage_check_id=?", Long.class, after.getArbitrageCheckId())).isEqualTo(initial);
        assertThat(jdbc.queryForObject("SELECT calculation_snapshot::text FROM fgc.arbitrage_check WHERE arbitrage_check_id=?", String.class, before.getArbitrageCheckId())).isEqualTo(beforeJson);
    }

    @Test
    void premiumCorrectionPreservesHistoricalReferencesAfterTerminationAndReorganization() {
        Long id = newContract();
        InsuranceContract original = latest(id);
        Long oldOrganization = original.getOrganizationId();
        Long agent = original.getAgentId();
        Long newOrganization = jdbc.queryForObject(
                "SELECT organization_id FROM fgc.organization WHERE organization_id <> ? ORDER BY organization_id LIMIT 1",
                Long.class, oldOrganization);
        jdbc.update("UPDATE fgc.agent SET organization_id=?, agent_status='TERMINATED', active_yn=FALSE, termination_date=? WHERE agent_id=?",
                newOrganization, original.getContractDate().plusDays(1), agent);
        jdbc.update("UPDATE fgc.organization SET active_yn=FALSE, effective_to=? WHERE organization_id=?",
                original.getContractDate().plusDays(1), oldOrganization);
        jdbc.update("UPDATE fgc.product_offering SET active_yn=FALSE WHERE product_offering_id=?", original.getProductOfferingId());
        jdbc.update("UPDATE fgc.insurer SET active_yn=FALSE WHERE insurer_id=?", original.getInsurerId());
        entityManager.clear();
        correct(id, "150000", null);
        InsuranceContract corrected = latest(id);
        assertThat(corrected.getAgentId()).isEqualTo(agent);
        assertThat(corrected.getOrganizationId()).isEqualTo(oldOrganization);
        assertThat(corrected.getFirstPremiumAmount()).isEqualByComparingTo("150000");
        assertThat(arbitrage.selectCalculationSource(id, corrected.getContractDate()).getCumulativePaidPremium())
                .isEqualByComparingTo("150000");
    }

    private Long insertRun() {
        return jdbc.queryForObject("""
                INSERT INTO fgc.validation_run(validation_month,run_no,run_type,status)
                SELECT DATE '2097-12-01', COALESCE(MAX(run_no),0)+1, 'PRE_CONFIRM', 'CREATED'
                FROM fgc.validation_run WHERE validation_month=DATE '2097-12-01'
                RETURNING validation_run_id
                """, Long.class);
    }

    private void insertExternal(Long id, LocalDate date, String type, String amount, int month) {
        jdbc.update("""
                INSERT INTO fgc.contract_financial_snapshot(contract_id,as_of_date,contract_month_no,
                    cumulative_paid_premium,surrender_value_type,source_ref)
                VALUES(?,?,?,?,?,'NORMALIZED_DB')
                """, id, date, month, new BigDecimal(amount), type);
    }

    private InsuranceContract latest(Long id) {
        InsuranceContract value = contractRepository.findById(id).orElseThrow();
        entityManager.refresh(value);
        return value;
    }

    private void correct(Long id, String amount, LocalDate date) {
        ContractUpdateRequest request = update(latest(id), amount);
        if (date != null) request.setContractDate(date);
        contracts.updateContract(id, request);
    }

    private Long newContract() {
        InsuranceContract seed = contractRepository.findById(jdbc.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no='FGC-FGL01-202607-0001'", Long.class)).orElseThrow();
        return contracts.createContract(new ContractCreateRequest(seed.getInsurerId(), "SNAP-" + UUID.randomUUID(),
                seed.getProductOfferingId(), seed.getContractDate(), ContractStatus.ACTIVE,
                seed.getAgentId(), seed.getOrganizationId(), PaymentCycleCode.MONTHLY,
                new BigDecimal("100000"), new BigDecimal("100000"), new BigDecimal("100000"),
                120, BigDecimal.ZERO)).contractId();
    }

    private ContractUpdateRequest update(InsuranceContract source, String text) {
        BigDecimal amount = new BigDecimal(text);
        return new ContractUpdateRequest(source.getContractNo(), source.getInsurerId(), source.getProductOfferingId(),
                source.getContractDate(), source.getCurrentStatus(), source.getAgentId(), source.getOrganizationId(),
                source.getPaymentCycleCode(), amount, amount, amount, source.getPaymentTermMonths(),
                source.getStandardSurrenderDeductionAmount(), source.getUpdatedAt());
    }
}
