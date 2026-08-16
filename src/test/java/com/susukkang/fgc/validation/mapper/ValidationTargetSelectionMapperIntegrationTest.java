package com.susukkang.fgc.validation.mapper;

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
class ValidationTargetSelectionMapperIntegrationTest {

    @Autowired ValidationTargetSelectionMapper mapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void selectsOnlySelectedContractsFromRequestedRunInContractOrder() {
        Long requestedRunId = insertValidationRun(LocalDate.of(2097, 1, 1));
        Long otherRunId = insertValidationRun(LocalDate.of(2097, 2, 1));
        List<Long> contractIds = jdbcTemplate.queryForList(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 3",
                Long.class);
        assertThat(contractIds).hasSize(3);

        insertTarget(requestedRunId, contractIds.get(2), "SELECTED");
        insertTarget(requestedRunId, contractIds.get(0), "SELECTED");
        insertTarget(requestedRunId, contractIds.get(1), "REVIEW_REQUIRED");
        insertTarget(otherRunId, contractIds.get(1), "SELECTED");

        List<Long> selected = mapper.selectSelectedContractIds(requestedRunId);

        assertThat(selected).containsExactly(contractIds.get(0), contractIds.get(2));
    }

    private Long insertValidationRun(LocalDate validationMonth) {
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

    private void insertTarget(Long validationRunId, Long contractId, String status) {
        jdbcTemplate.update("""
                INSERT INTO fgc.validation_target (
                    validation_run_id, contract_id, product_offering_id,
                    selection_status, selection_reason
                )
                SELECT ?, contract_id, product_offering_id, ?, 'TEST'
                  FROM fgc.insurance_contract
                 WHERE contract_id = ?
                """, validationRunId, status, contractId);
    }
}
