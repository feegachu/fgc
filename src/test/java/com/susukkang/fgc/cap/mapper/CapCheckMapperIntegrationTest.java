package com.susukkang.fgc.cap.mapper;

import com.susukkang.fgc.cap.dto.CapAgentSummaryRow;
import com.susukkang.fgc.cap.dto.CapCheckListRow;
import com.susukkang.fgc.cap.dto.CapCheckStatusCount;
import com.susukkang.fgc.cap.dto.CapStageSummaryRow;
import com.susukkang.fgc.common.code.PaymentStage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CapCheckMapperIntegrationTest {

    @Autowired CapCheckMapper capCheckMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    // REG-10, 운영정책 §7-2: 귀속행별 원 단위 반올림 후 DEDUCTION을 차감한다.
    @Test
    void sumsRoundedComplianceEvidenceWithDeductionSignWithinFirstYearAndStage() {
        TestContract contract = insertTestContract();

        insertEvidence(contract, PaymentStage.GA_TO_FC, "PAYMENT", new BigDecimal("10.50"),
                contract.contractDate(), "EVIDENCE-1");
        insertEvidence(contract, PaymentStage.GA_TO_FC, "PAYMENT", new BigDecimal("10.50"),
                contract.contractDate().plusMonths(1), "EVIDENCE-2");
        insertEvidence(contract, PaymentStage.GA_TO_FC, "DEDUCTION", new BigDecimal("3.50"),
                contract.contractDate().plusMonths(2), "EVIDENCE-3");

        // 초년도 마지막 날은 포함한다.
        insertEvidence(contract, PaymentStage.GA_TO_FC, "PAYMENT", new BigDecimal("5.50"),
                contract.contractDate().plusMonths(12).minusDays(1), "LAST-DAY-FIRST-YEAR");

        // 다른 지급 단계, 1주년 당일, 증빙 없는 행은 집계에서 제외한다.
        insertEvidence(contract, PaymentStage.INSURER_TO_GA, "PAYMENT", new BigDecimal("100.00"),
                contract.contractDate(), "OTHER-STAGE");
        insertEvidence(contract, PaymentStage.GA_TO_FC, "PAYMENT", new BigDecimal("100.00"),
                contract.contractDate().plusMonths(12), "OUTSIDE-FIRST-YEAR");
        insertEvidence(contract, PaymentStage.GA_TO_FC, "PAYMENT", new BigDecimal("100.00"),
                contract.contractDate().plusMonths(3), null);

        BigDecimal amount = capCheckMapper.selectComplianceEvidenceAmount(
                contract.contractId(), PaymentStage.GA_TO_FC);

        assertThat(amount).isEqualByComparingTo("24"); // ROUND(10.5)+ROUND(10.5)-ROUND(3.5)+ROUND(5.5)
    }

    @Test
    void returnsNullWhenNoComplianceEvidenceExists() {
        TestContract contract = insertTestContract();

        assertThat(capCheckMapper.selectComplianceEvidenceAmount(
                contract.contractId(), PaymentStage.GA_TO_FC)).isNull();
    }

    // REG-19: GA_TO_FC 룰 시행일 이전 계약은 실패가 아니라 해당 단계 검증 미적용이다.
    @Test
    void distinguishesContractsBeforeAndAfterGaToFcRuleEffectiveDate() {
        Long beforeEffectiveDate = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = 'FGC-FGL02-202605-0001'",
                Long.class);
        Long afterEffectiveDate = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = 'FGC-FGL01-202607-0001'",
                Long.class);

        assertThat(capCheckMapper.existsApplicableRuleSet(
                beforeEffectiveDate, PaymentStage.GA_TO_FC)).isFalse();
        assertThat(capCheckMapper.existsApplicableRuleSet(
                afterEffectiveDate, PaymentStage.GA_TO_FC)).isTrue();
    }

    @Test
    void capDashboardAggregatesLatestResultsAcrossFullOrganizationScope() {
        TestContract first = insertTestContract();
        TestContract second = insertTestContractFromAnotherOrganization(first.organizationId());
        LocalDate asOfDate = LocalDate.of(2035, 1, 31);

        insertCapCheck(first, PaymentStage.GA_TO_FC, asOfDate,
                "1000", "200", "0", "20.000000", "NORMAL",
                OffsetDateTime.parse("2035-01-01T00:00:00Z"));
        insertCapCheck(first, PaymentStage.GA_TO_FC, asOfDate,
                "1000", "900", "0", "90.000000", "WARNING",
                OffsetDateTime.parse("2035-01-02T00:00:00Z"));
        insertCapCheck(first, PaymentStage.INSURER_TO_GA, asOfDate,
                "1000", "1100", "30", "110.000000", "VIOLATION",
                OffsetDateTime.parse("2035-01-03T00:00:00Z"));
        insertCapCheck(second, PaymentStage.GA_TO_FC, asOfDate,
                "2000", "1000", "0", "50.000000", "NORMAL",
                OffsetDateTime.parse("2035-01-04T00:00:00Z"));

        List<CapCheckListRow> oldNormalRows = capCheckMapper.search(
                LocalDate.of(2035, 1, 1), PaymentStage.GA_TO_FC.name(), "NORMAL",
                null, first.organizationId(), null, 0, 20);
        assertThat(oldNormalRows).isEmpty();

        long organizationTotal = capCheckMapper.count(
                LocalDate.of(2035, 1, 1), null, null,
                null, first.organizationId(), null);
        assertThat(organizationTotal).isEqualTo(2);

        List<CapCheckStatusCount> agentStageKpis = capCheckMapper.summarize(
                LocalDate.of(2035, 1, 1), PaymentStage.GA_TO_FC.name(),
                null, first.organizationId(), null);
        assertThat(agentStageKpis).singleElement().satisfies(count -> {
            assertThat(count.getResultStatus()).isEqualTo("WARNING");
            assertThat(count.getCount()).isEqualTo(1);
        });

        List<CapStageSummaryRow> stages = capCheckMapper.summarizeByStage(
                LocalDate.of(2035, 1, 1), null, first.organizationId(), null);
        assertThat(stages).hasSize(2);

        CapStageSummaryRow insurerStage = stage(stages, PaymentStage.INSURER_TO_GA);
        assertThat(insurerStage.getContractCount()).isEqualTo(1);
        assertThat(insurerStage.getLimitAmountTotal()).isEqualByComparingTo("1000");
        assertThat(insurerStage.getIncludedAmountTotal()).isEqualByComparingTo("1100");
        assertThat(insurerStage.getComplianceDeductionAmountTotal()).isEqualByComparingTo("30");
        assertThat(insurerStage.getUsagePct()).isEqualByComparingTo("110.000000");
        assertThat(insurerStage.getViolationCount()).isEqualTo(1);
        assertThat(insurerStage.getWorstContractNo()).isEqualTo(first.contractNo());

        CapStageSummaryRow agentStage = stage(stages, PaymentStage.GA_TO_FC);
        assertThat(agentStage.getContractCount()).isEqualTo(1);
        assertThat(agentStage.getIncludedAmountTotal()).isEqualByComparingTo("900");
        assertThat(agentStage.getComplianceDeductionAmountTotal()).isZero();
        assertThat(agentStage.getUsagePct()).isEqualByComparingTo("90.000000");
        assertThat(agentStage.getWarningCount()).isEqualTo(1);

        List<CapAgentSummaryRow> agents = capCheckMapper.summarizeByAgent(
                LocalDate.of(2035, 1, 1), null, first.organizationId(), null);
        assertThat(agents).singleElement().satisfies(agent -> {
            assertThat(agent.getAgentId()).isEqualTo(first.agentId());
            assertThat(agent.getOrganizationId()).isEqualTo(first.organizationId());
            assertThat(agent.getContractCount()).isEqualTo(1);
            assertThat(agent.getLimitAmountTotal()).isEqualByComparingTo("1000");
            assertThat(agent.getIncludedAmountTotal()).isEqualByComparingTo("900");
            assertThat(agent.getUsagePct()).isEqualByComparingTo("90.000000");
        });
    }

    private TestContract insertTestContract() {
        String contractNo = "IT-CAP-EVIDENCE-" + UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date,
                    agent_id, organization_id, premium_per_cycle_amount,
                    first_premium_amount, monthly_equivalent_first_premium,
                    premium_conversion_rule_code, payment_cycle_code,
                    payment_term_months, current_status, data_origin
                )
                SELECT insurer_id, product_offering_id, ?, DATE '2035-01-15',
                       agent_id, organization_id, premium_per_cycle_amount,
                       first_premium_amount, monthly_equivalent_first_premium,
                       premium_conversion_rule_code, payment_cycle_code,
                       payment_term_months, 'ACTIVE', 'MANUAL'
                  FROM fgc.insurance_contract
                 ORDER BY contract_id
                 LIMIT 1
                RETURNING contract_id, contract_no, contract_date, agent_id, organization_id
                """,
                (rs, rowNum) -> new TestContract(
                        rs.getLong("contract_id"),
                        rs.getString("contract_no"),
                        rs.getObject("contract_date", LocalDate.class),
                        rs.getLong("agent_id"),
                        rs.getLong("organization_id")),
                contractNo);
    }

    private TestContract insertTestContractFromAnotherOrganization(Long excludedOrganizationId) {
        String contractNo = "IT-CAP-SUMMARY-" + UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date,
                    agent_id, organization_id, premium_per_cycle_amount,
                    first_premium_amount, monthly_equivalent_first_premium,
                    premium_conversion_rule_code, payment_cycle_code,
                    payment_term_months, current_status, data_origin
                )
                SELECT insurer_id, product_offering_id, ?, DATE '2035-01-15',
                       agent_id, organization_id, premium_per_cycle_amount,
                       first_premium_amount, monthly_equivalent_first_premium,
                       premium_conversion_rule_code, payment_cycle_code,
                       payment_term_months, 'ACTIVE', 'MANUAL'
                  FROM fgc.insurance_contract
                 WHERE organization_id <> ?
                 ORDER BY contract_id
                 LIMIT 1
                RETURNING contract_id, contract_no, contract_date, agent_id, organization_id
                """,
                (rs, rowNum) -> new TestContract(
                        rs.getLong("contract_id"),
                        rs.getString("contract_no"),
                        rs.getObject("contract_date", LocalDate.class),
                        rs.getLong("agent_id"),
                        rs.getLong("organization_id")),
                contractNo, excludedOrganizationId);
    }

    private void insertCapCheck(
            TestContract contract,
            PaymentStage stage,
            LocalDate asOfDate,
            String limitAmount,
            String includedAmount,
            String complianceDeductionAmount,
            String usagePct,
            String resultStatus,
            OffsetDateTime checkedAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO fgc.cap_check (
                    contract_id, payment_stage, cap_rule_set_id, check_kind, as_of_date,
                    base_premium_amount, refund_12m_amount, compliance_deduction_amount,
                    limit_amount, included_amount, remaining_amount, usage_pct,
                    result_status, calculation_snapshot, checked_at
                )
                SELECT ?, ?, cap_rule_set_id, 'REALTIME', ?,
                       100, 0, ?, ?, ?, ?::numeric - ?::numeric, ?,
                       ?, '{}'::jsonb, ?
                  FROM fgc.cap_rule_set
                 WHERE payment_stage = ?
                 ORDER BY cap_rule_set_id
                 LIMIT 1
                """,
                contract.contractId(), stage.name(), asOfDate,
                new BigDecimal(complianceDeductionAmount),
                new BigDecimal(limitAmount), new BigDecimal(includedAmount),
                limitAmount, includedAmount, new BigDecimal(usagePct),
                resultStatus, checkedAt, stage.name());
    }

    private CapStageSummaryRow stage(List<CapStageSummaryRow> stages, PaymentStage stage) {
        return stages.stream()
                .filter(row -> stage.name().equals(row.getPaymentStage()))
                .findFirst()
                .orElseThrow();
    }

    private void insertEvidence(
            TestContract contract,
            PaymentStage paymentStage,
            String cashflowType,
            BigDecimal amount,
            LocalDate attributionDate,
            String evidenceRef
    ) {
        Long commissionItemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1",
                Long.class);
        Long transactionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    payment_stage, source_type, source_business_key, recipient_agent_id,
                    commission_item_id, settlement_month, amount, cashflow_type, status
                ) VALUES (?, 'GA_MANUAL_PAYMENT', ?, ?, ?,
                          date_trunc('month', ?::date)::date, ?, ?, 'DRAFT')
                RETURNING commission_transaction_id
                """, Long.class,
                paymentStage.name(), "IT-CAP-EVIDENCE-" + UUID.randomUUID(),
                contract.agentId(), commissionItemId, attributionDate, amount, cashflowType);
        jdbcTemplate.update("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id, attribution_seq, attribution_scope,
                    contract_id, agent_id, attribution_date, attribution_month,
                    attributed_amount, inclusion_status_snapshot, exclusion_type_snapshot,
                    attribution_method, allocation_basis_snapshot, evidence_ref
                ) VALUES (?, 1, 'CONTRACT', ?, ?, ?,
                          date_trunc('month', ?::date)::date, ?, 'EXCLUDED', 'COMPLIANCE_3PCT',
                          'DIRECT', '{}'::jsonb, ?)
                """, transactionId, contract.contractId(), contract.agentId(), attributionDate,
                attributionDate, amount, evidenceRef);
        jdbcTemplate.update("""
                UPDATE fgc.commission_transaction
                   SET status = 'CONFIRMED'
                 WHERE commission_transaction_id = ?
                """, transactionId);
    }

    private record TestContract(
            Long contractId,
            String contractNo,
            LocalDate contractDate,
            Long agentId,
            Long organizationId
    ) {
    }
}
