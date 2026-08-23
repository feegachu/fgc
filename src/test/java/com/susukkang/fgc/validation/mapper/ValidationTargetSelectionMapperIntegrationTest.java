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
     * FGC-FUN-042 데모 시드 기준 회귀 — insertTargets가 환급률표의 source_product_code(표준상품코드,
     * 시드데이터 명세서 §환급률표 필수 조합)를 product.standard_product_code와 비교해야 한다.
     * 보험사 상품코드(insurer_product_code)와 비교하던 버그에서는 전 계약이
     * "상품코드 불일치" REVIEW_REQUIRED로 빠져 하위 검증이 전부 비었다(대시보드 0건의 원인).
     */
    @Test
    void fgcFun042InsertTargetsSelectsSeedContractsByStandardProductCode() {
        LocalDate validationMonth = LocalDate.of(2026, 7, 1);
        Long runId = insertValidationRun(validationMonth);

        int inserted = mapper.insertTargets(runId, validationMonth, LocalDate.of(2026, 7, 31));

        assertThat(inserted).isPositive();
        List<String> selectedContractNos = jdbcTemplate.queryForList("""
                SELECT ic.contract_no
                  FROM fgc.validation_target vt
                  JOIN fgc.insurance_contract ic ON ic.contract_id = vt.contract_id
                 WHERE vt.validation_run_id = ? AND vt.selection_status = 'SELECTED'
                """, String.class, runId);
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
        // 판정에 실제로 쓴 코드가 snapshot 증거로 남아야 한다 — 키 교체(insurerProductCode
        // → standardProductCode)가 되돌아가면 여기서 잡힌다.
        long withoutStandardCodeEvidence = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.validation_target
                 WHERE validation_run_id = ?
                   AND (snapshot ->> 'standardProductCode' IS NULL
                        OR snapshot ->> 'insurerProductCode' IS NOT NULL)
                """, Long.class, runId);

        assertThat(selectedContractNos).containsExactlyInAnyOrder(
                "FGC-FGL01-202607-0001",
                "FGC-FGL01-202607-0002",
                "FGC-FGL01-202607-0003",
                "FGC-FGL01-202607-0004",
                "FGC-FGL01-202607-0005",
                "FGC-FGL02-202605-0001");
        assertThat(selected).isPositive();          // 시드의 정상 시나리오 계약이 선정돼야 한다
        assertThat(productCodeMismatch).isZero();   // 시드 정본 코드 체계에서 불일치는 없어야 한다
        assertThat(selectedWithoutTable).isZero();  // 선정 건은 환급률표가 반드시 배정된다
        assertThat(withoutStandardCodeEvidence).isZero();
    }

    /**
     * #332 회귀 — CONT-W03 에서 선택 가능한 STD-SAV-A(V4 경계시험 판매버전)에
     * 환급률표 시드(V42)가 없으면 등록 계약이 "예상 해약환급률표 없음" REVIEW_REQUIRED 로
     * 빠져 시연 컷③→⑧ 이음매가 끊어진다. 시드 추가 후 SELECTED 로 선정되는지 고정한다.
     */
    @Test
    void fgc332SavContractIsSelectedWithSeededRefundRateTable() {
        Long contractId = insertSavContract();
        Long runId = insertValidationRun(LocalDate.of(2098, 3, 1));

        mapper.insertTargets(runId, LocalDate.of(2098, 3, 1), LocalDate.of(2098, 3, 31));

        var target = jdbcTemplate.queryForMap("""
                SELECT selection_status, selection_reason, refund_rate_table_id
                  FROM fgc.validation_target
                 WHERE validation_run_id = ? AND contract_id = ?
                """, runId, contractId);
        assertThat(target.get("selection_status")).isEqualTo("SELECTED");
        assertThat(target.get("selection_reason")).isEqualTo("월 통합검증 대상");
        assertThat(target.get("refund_rate_table_id")).isNotNull();
    }

    private Long insertSavContract() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date,
                    agent_id, organization_id, premium_per_cycle_amount,
                    first_premium_amount, monthly_equivalent_first_premium, payment_term_months
                )
                SELECT p.insurer_id, po.product_offering_id, 'IT-332-SAV-0001', DATE '2098-02-15',
                       a.agent_id, a.organization_id, 200000, 200000, 200000, 120
                  FROM fgc.product p
                  JOIN fgc.product_offering po ON po.product_id = p.product_id
                                              AND po.offering_version = '2026-CURRENT-A'
                                              AND po.channel_code = 'FACE_TO_FACE'
                  CROSS JOIN LATERAL (
                       SELECT agent_id, organization_id FROM fgc.agent ORDER BY agent_id LIMIT 1
                  ) a
                 WHERE p.standard_product_code = 'STD-SAV-A'
                RETURNING contract_id
                """, Long.class);
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
