package com.susukkang.fgc.validation.mapper;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.validation.dto.ValidationScheduleState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ValidationScheduleMapperIntegrationTest {

    @Autowired ValidationScheduleMapper mapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void selectsBothPaymentStagesForSelectedContract() {
        Long validationRunId = insertValidationRun();
        Long contractId = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1",
                Long.class);
        jdbcTemplate.update("""
                INSERT INTO fgc.validation_target (
                    validation_run_id, contract_id, product_offering_id,
                    selection_status, selection_reason
                )
                SELECT ?, contract_id, product_offering_id, 'SELECTED', 'TEST'
                  FROM fgc.insurance_contract
                 WHERE contract_id = ?
                """, validationRunId, contractId);

        List<ValidationScheduleState> states = mapper.selectScheduleStates(validationRunId);

        assertThat(states).hasSize(2);
        assertThat(states).extracting(ValidationScheduleState::getPaymentStage)
                .containsExactlyInAnyOrder(PaymentStage.INSURER_TO_GA, PaymentStage.GA_TO_FC);
        assertThat(states).allSatisfy(state -> {
            assertThat(state.getContractId()).isEqualTo(contractId);
            assertThat(state.getActiveHeaderCount()).isNotNull();
            assertThat(state.getLineCount()).isNotNull();
            assertThat(state.getDistinctLineCount()).isNotNull();
        });
    }

    @Test
    void sumsScheduleAmountsAfterRoundingEachLineToWon() {
        Long validationRunId = insertValidationRun();
        Long contractId = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1",
                Long.class);
        LocalDate contractDate = jdbcTemplate.queryForObject(
                "SELECT contract_date FROM fgc.insurance_contract WHERE contract_id = ?",
                LocalDate.class, contractId);
        Long policyVersionId = jdbcTemplate.queryForObject(
                "SELECT policy_version_id FROM fgc.policy_version WHERE status = 'ACTIVE' ORDER BY policy_version_id LIMIT 1",
                Long.class);
        Long commissionItemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1",
                Long.class);

        jdbcTemplate.update("""
                INSERT INTO fgc.validation_target (
                    validation_run_id, contract_id, product_offering_id,
                    selection_status, selection_reason
                )
                SELECT ?, contract_id, product_offering_id, 'SELECTED', 'ROUNDING_TEST'
                  FROM fgc.insurance_contract
                 WHERE contract_id = ?
                """, validationRunId, contractId);
        jdbcTemplate.update("""
                UPDATE fgc.schedule_header
                   SET active_yn = false
                 WHERE contract_id = ?
                   AND payment_stage = 'GA_TO_FC'
                   AND schedule_purpose = 'OPERATIONAL'
                   AND active_yn = true
                """, contractId);
        Long scheduleHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header (
                    contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_regime, schedule_purpose, active_yn
                )
                VALUES (?, 'GA_TO_FC', ?, 999, 'CURRENT', 'OPERATIONAL', true)
                RETURNING schedule_header_id
                """, Long.class, contractId, policyVersionId);
        jdbcTemplate.update("""
                INSERT INTO fgc.schedule_line (
                    schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, basis_code, basis_amount, calculation_type,
                    rate_pct, expected_amount
                ) VALUES
                    (?, 1, 1, 1, ?, ?, 'TEST_AMOUNT', 10.40, 'RATE', 100.000000, 10.40),
                    (?, 2, 2, 1, ?, ?, 'TEST_AMOUNT', 10.40, 'RATE', 100.000000, 10.40)
                """, scheduleHeaderId, contractDate, commissionItemId,
                scheduleHeaderId, contractDate, commissionItemId);

        ValidationScheduleState gaToFc = mapper.selectScheduleStates(validationRunId).stream()
                .filter(state -> state.getPaymentStage() == PaymentStage.GA_TO_FC)
                .findFirst()
                .orElseThrow();

        assertThat(gaToFc.getTotalAmount()).isEqualByComparingTo(new BigDecimal("20"));
    }

    private Long insertValidationRun() {
        LocalDate validationMonth = LocalDate.of(2096, 6, 1);
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (
                    validation_month, run_no, run_type, status
                )
                SELECT ?, COALESCE(MAX(run_no), 0) + 1, 'MONTHLY', 'CREATED'
                  FROM fgc.validation_run
                 WHERE validation_month = ?
                RETURNING validation_run_id
                """, Long.class, validationMonth, validationMonth);
    }
}
