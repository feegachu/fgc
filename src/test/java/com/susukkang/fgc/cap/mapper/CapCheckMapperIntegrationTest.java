package com.susukkang.fgc.cap.mapper;

import com.susukkang.fgc.common.code.PaymentStage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
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
                RETURNING contract_id, contract_date, agent_id
                """,
                (rs, rowNum) -> new TestContract(
                        rs.getLong("contract_id"),
                        rs.getObject("contract_date", LocalDate.class),
                        rs.getLong("agent_id")),
                contractNo);
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

    private record TestContract(Long contractId, LocalDate contractDate, Long agentId) {
    }
}
