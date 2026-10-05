package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.dto.CapCheckDetailInsertRow;
import com.susukkang.fgc.cap.dto.CapCheckInsertRow;
import com.susukkang.fgc.cap.entity.CapCheck;
import com.susukkang.fgc.cap.entity.CapCheckDetail;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.transaction.domain.CapCheckCommand;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 PostgreSQL UPSERT·스냅샷·JPA 영속성 컨텍스트 가시성을 검증한다. */
@SpringBootTest
@Transactional
class CapCheckWriteRepositoryIntegrationTest {
    @Autowired CapCheckWriteRepository writes;
    @Autowired CapCheckQueryRepository queries;
    @Autowired CapCheckRepository checks;
    @Autowired CapCheckDetailRepository details;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void monthlyRetryReusesIdsUpdatesManagedResultsAndPrunesOnlyObsoleteDetails() {
        Fixture fixture = fixture();
        Long runId = jdbc.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES ('2035-04-01', 9373, 'MANUAL_CONTRACT')
                RETURNING validation_run_id
                """, Long.class);
        CapCheckInsertRow row = row(fixture, runId);
        writes.insertCapCheck(row);
        Long checkId = row.getCapCheckId();
        writes.insertCapCheckDetails(List.of(detail(fixture, checkId, 1, "100.25"),
                detail(fixture, checkId, 2, "50.00")));
        CapCheck before = checks.findById(checkId).orElseThrow();
        Long detailId = detailId(checkId, 1);
        CapCheckDetail beforeDetail = details.findById(detailId).orElseThrow();

        row.setIncludedAmount(new BigDecimal("1234.56"));
        row.setRemainingAmount(new BigDecimal("-234.56"));
        row.setUsagePct(new BigDecimal("123.456789"));
        row.setResultStatus("VIOLATION");
        row.setCalculationSnapshotJson("{\"source\":\"retry\"}");
        writes.insertCapCheck(row);
        writes.pruneCapCheckDetails(checkId, 1);
        writes.insertCapCheckDetails(List.of(detail(fixture, checkId, 1, "1234.56")));

        assertThat(row.getCapCheckId()).isEqualTo(checkId);
        assertThat(entityManager.contains(before)).isFalse();
        assertThat(entityManager.contains(beforeDetail)).isFalse();
        CapCheck after = checks.findById(checkId).orElseThrow();
        assertThat(after.getResultStatus()).isEqualTo(CapResultStatus.VIOLATION);
        assertThat(after.getIncludedAmount()).isEqualByComparingTo("1234.56");
        assertThat(after.getRemainingAmount()).isEqualByComparingTo("-234.56");
        assertThat(after.getUsagePct()).isEqualByComparingTo("123.456789");
        assertThat(after.getCheckedAt()).isAfter(before.getCheckedAt());
        assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
        assertThat(after.getCalculationSnapshotJson()).contains("retry");
        assertThat(after.getRefundRateTableId()).isNull();
        assertThat(detailId(checkId, 1)).isEqualTo(detailId);
        assertThat(details.findById(detailId).orElseThrow().getAmount()).isEqualByComparingTo("1234.56");
        assertThat(queries.findDetailsByCapCheckId(checkId)).singleElement().satisfies(line -> {
            assertThat(line.itemCode()).isEqualTo("SNAPSHOT-CODE");
            assertThat(line.itemName()).isEqualTo("계산 당시 항목명");
            assertThat(line.contractMonthNo()).isNull();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.cap_check WHERE validation_run_id = ?",
                Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void realtimeAppendsRowsAndBulkDeleteFlushesPendingEntityChangesBeforeClearing() {
        Fixture fixture = fixture();
        CapCheckInsertRow first = row(fixture, null);
        first.setCalculationSnapshotJson(null);
        first.setUsagePct(null);
        writes.insertCapCheck(first);
        CapCheckInsertRow second = row(fixture, null);
        writes.insertCapCheck(second);
        assertThat(first.getCapCheckId()).isNotEqualTo(second.getCapCheckId());
        CapCheck managed = checks.findById(first.getCapCheckId()).orElseThrow();
        assertThat(managed.getCalculationSnapshotJson()).isEqualTo("{}");
        assertThat(managed.getUsagePct()).isNull();
        assertThat(managed.getCheckedAt()).isNotNull();
        assertThat(managed.getCreatedAt()).isNotNull();

        ReflectionTestUtils.setField(managed, "calculationSnapshotJson", "{\"pending\":true}");
        writes.pruneCapCheckDetails(first.getCapCheckId(), 0);

        assertThat(entityManager.contains(managed)).isFalse();
        assertThat(checks.findById(first.getCapCheckId()).orElseThrow().getCalculationSnapshotJson())
                .contains("\"pending\": true");
    }

    @Test
    void preConfirmCandidateIsHiddenUntilConfirmedAndDetachingPreservesEvidenceSnapshots() {
        Fixture fixture = fixture();
        CapCheckInsertRow baseline = row(fixture, null);
        writes.insertCapCheck(baseline);
        Long paymentId = jdbc.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    payment_stage, source_type, source_business_key, recipient_agent_id,
                    commission_item_id, settlement_month, amount, cashflow_type, status)
                VALUES ('GA_TO_FC', 'GA_MANUAL_PAYMENT', ?, ?, ?, '2035-04-01', 10, 'PAYMENT', 'DRAFT')
                RETURNING commission_transaction_id
                """, Long.class, "IT-CAP-JPA-" + UUID.randomUUID(), fixture.agentId(), fixture.itemId());
        Long attributionId = jdbc.queryForObject("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id, attribution_seq, attribution_scope, contract_id,
                    agent_id, attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, attribution_method, allocation_basis_snapshot)
                VALUES (?, 1, 'CONTRACT', ?, ?, '2035-04-15', '2035-04-01', 10,
                        'INCLUDED', 'DIRECT', '{}'::jsonb)
                RETURNING transaction_attribution_id
                """, Long.class, paymentId, fixture.contractId(), fixture.agentId());
        CapCheckCommand candidate = CapCheckCommand.builder()
                .paymentId(paymentId).contractId(fixture.contractId()).paymentStage("GA_TO_FC")
                .capRuleSetId(fixture.ruleId()).asOfDate(LocalDate.of(2035, 4, 30))
                .basePremiumAmount(new BigDecimal("100")).refund12mAmount(BigDecimal.ZERO)
                .complianceDeductionAmount(BigDecimal.ZERO).limitAmount(new BigDecimal("1000"))
                .includedAmount(new BigDecimal("1010")).remainingAmount(new BigDecimal("-10"))
                .usagePct(new BigDecimal("101.000000")).resultStatus(CapResultStatus.VIOLATION)
                .calculationSnapshotJson("{\"candidate\":true}").build();
        writes.insertCapCheck(candidate);
        CapCheckDetailInsertRow detail = detail(fixture, candidate.getCapCheckId(), 1, "10.00");
        detail.setTransactionAttributionId(attributionId);
        detail.setEvidenceRef("CONFIRM-EVIDENCE");
        writes.insertCapCheckDetails(List.of(detail));

        assertThat(queries.findLatestByContractAndStage(fixture.contractId(), "GA_TO_FC").getCapCheckId())
                .isEqualTo(baseline.getCapCheckId());
        assertThat(queries.findById(candidate.getCapCheckId()).getCalculationSnapshotJson()).contains("candidate");
        assertThat(queries.search(null, "GA_TO_FC", "VIOLATION", null, null, fixture.contractNo(), 0, 10))
                .isEmpty();
        CapCheckDetail managed = details.findById(detailId(candidate.getCapCheckId(), 1)).orElseThrow();
        writes.detachPreConfirmDetails(paymentId);
        assertThat(entityManager.contains(managed)).isFalse();
        CapCheckDetail detached = details.findById(managed.getCapCheckDetailId()).orElseThrow();
        assertThat(detached.getTransactionAttributionId()).isNull();
        assertThat(detached.getEvidenceRef()).isEqualTo("CONFIRM-EVIDENCE");
        assertThat(detached.getItemName()).isEqualTo("계산 당시 항목명");

        jdbc.update("UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = ?",
                paymentId);
        assertThat(queries.findLatestByContractAndStage(fixture.contractId(), "GA_TO_FC").getCapCheckId())
                .isEqualTo(candidate.getCapCheckId());
        assertThat(queries.search(null, "GA_TO_FC", "NORMAL", null, null, fixture.contractNo(), 0, 10))
                .isEmpty();
        assertThat(queries.count(null, "GA_TO_FC", "VIOLATION", null, null, fixture.contractNo()))
                .isEqualTo(1);
    }

    private Long detailId(Long checkId, int sequence) {
        return jdbc.queryForObject("SELECT cap_check_detail_id FROM fgc.cap_check_detail WHERE cap_check_id = ? AND detail_seq = ?",
                Long.class, checkId, sequence);
    }

    private CapCheckDetailInsertRow detail(Fixture fixture, Long checkId, int sequence, String amount) {
        return CapCheckDetailInsertRow.builder().capCheckId(checkId).detailSeq(sequence)
                .commissionItemId(fixture.itemId()).itemCode("SNAPSHOT-CODE").itemName("계산 당시 항목명")
                .classificationSnapshot("INCLUDED").amount(new BigDecimal(amount))
                .decisionReason("계산 근거 보존").build();
    }

    private CapCheckInsertRow row(Fixture fixture, Long runId) {
        return CapCheckInsertRow.builder().validationRunId(runId).contractId(fixture.contractId())
                .paymentStage("GA_TO_FC").capRuleSetId(fixture.ruleId())
                .checkKind(runId == null ? "REALTIME" : "MONTHLY").asOfDate(LocalDate.of(2035, 4, 30))
                .basePremiumAmount(new BigDecimal("100")).refund12mAmount(BigDecimal.ZERO)
                .complianceDeductionAmount(BigDecimal.ZERO).limitAmount(new BigDecimal("1000"))
                .includedAmount(new BigDecimal("150.25")).remainingAmount(new BigDecimal("849.75"))
                .usagePct(new BigDecimal("15.025000")).resultStatus("NORMAL")
                .calculationSnapshotJson("{}").build();
    }

    private Fixture fixture() {
        String contractNo = "IT-CAP-JPA-" + UUID.randomUUID();
        Long contractId = jdbc.queryForObject("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date, agent_id, organization_id,
                    premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                    premium_conversion_rule_code, payment_cycle_code, payment_term_months, current_status, data_origin)
                SELECT insurer_id, product_offering_id, ?, '2035-04-15', agent_id, organization_id,
                       premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                       premium_conversion_rule_code, payment_cycle_code, payment_term_months, 'ACTIVE', 'MANUAL'
                  FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1
                RETURNING contract_id
                """, Long.class, contractNo);
        Long agentId = jdbc.queryForObject("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?",
                Long.class, contractId);
        Long itemId = jdbc.queryForObject("SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1",
                Long.class);
        Long ruleId = jdbc.queryForObject("SELECT cap_rule_set_id FROM fgc.cap_rule_set WHERE payment_stage = 'GA_TO_FC' ORDER BY cap_rule_set_id LIMIT 1",
                Long.class);
        return new Fixture(contractId, contractNo, agentId, itemId, ruleId);
    }

    private record Fixture(Long contractId, String contractNo, Long agentId, Long itemId, Long ruleId) {
    }
}
