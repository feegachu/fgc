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

    /**
     * 데모 시드 기준 회귀 — insertTargets가 환급률표의 source_product_code(표준상품코드,
     * 시드데이터 명세서 §환급률표 필수 조합)를 product.standard_product_code와 비교해야 한다.
     * 보험사 상품코드(insurer_product_code)와 비교하던 버그에서는 전 계약이
     * "상품코드 불일치" REVIEW_REQUIRED로 빠져 하위 검증이 전부 비었다(대시보드 0건의 원인).
     */
    @Test
    void insertTargetsSelectsSeedContractsByStandardProductCode() {
        Long runId = insertValidationRun(LocalDate.of(2098, 1, 1));

        int inserted = mapper.insertTargets(runId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(inserted).isPositive();
        long selected = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.validation_target
                 WHERE validation_run_id = ? AND selection_status = 'SELECTED'
                """, Long.class, runId);
        long productCodeMismatch = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.validation_target
                 WHERE validation_run_id = ? AND selection_reason = '상품코드 불일치'
                """, Long.class, runId);
        long selectedWithoutTable = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.validation_target
                 WHERE validation_run_id = ? AND selection_status = 'SELECTED'
                   AND refund_rate_table_id IS NULL
                """, Long.class, runId);

        assertThat(selected).isPositive();          // 시드의 정상 시나리오 계약이 선정돼야 한다
        assertThat(productCodeMismatch).isZero();   // 시드 정본 코드 체계에서 불일치는 없어야 한다
        assertThat(selectedWithoutTable).isZero();  // 선정 건은 환급률표가 반드시 배정된다
    }

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
