package com.susukkang.fgc.validation.mapper;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.validation.dto.ValidationScheduleState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

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
