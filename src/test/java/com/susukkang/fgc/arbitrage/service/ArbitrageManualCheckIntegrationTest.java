package com.susukkang.fgc.arbitrage.service;

import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckInsertDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 계약 단건 차익거래 수동 검증의 실행 생성부터 결과 저장까지 검증한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@SpringBootTest
@Transactional
class ArbitrageManualCheckIntegrationTest {
    @Autowired
    private ArbitrageService arbitrageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 설명 : 금융 스냅샷이 있는 계약을 수동 검증하여 실행과 결과가 함께 저장되는지 검증한다.
     *
     * @author hjKang
     * @since 2026-08-12
     */
    @Test
    void createsArbitrageCheckInExistingValidationRun() {
        TestReference reference = testReference();
        Long validationRunId = insertValidationRun();

        ArbitrageCheckInsertDTO result = arbitrageService.checkInExistingRun(
                validationRunId, reference.contractId(), reference.asOfDate());

        Long savedCheckId = jdbcTemplate.queryForObject("""
                SELECT arbitrage_check_id
                  FROM fgc.arbitrage_check
                 WHERE arbitrage_check_id = ?
                   AND contract_id = ?
                   AND validation_run_id = ?
                """, Long.class,
                result.getArbitrageCheckId(),
                reference.contractId(),
                validationRunId);

        assertThat(result.getArbitrageCheckId()).isNotNull();
        assertThat(result.getResultStatus()).isNotNull();
        assertThat(savedCheckId).isEqualTo(result.getArbitrageCheckId());
    }

    private TestReference testReference() {
        return jdbcTemplate.queryForObject("""
                SELECT cfs.contract_id,
                       MAX(cfs.as_of_date) AS as_of_date,
                       (SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1) AS user_id
                  FROM fgc.contract_financial_snapshot cfs
                 GROUP BY cfs.contract_id
                 ORDER BY cfs.contract_id
                 LIMIT 1
                """, (resultSet, rowNum) -> new TestReference(
                resultSet.getLong("contract_id"),
                resultSet.getObject("as_of_date", LocalDate.class),
                resultSet.getLong("user_id")));
    }

    private Long insertValidationRun() {
        LocalDate month = LocalDate.of(2097, 12, 1);
        Integer runNo = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(run_no), 0) + 1
                  FROM fgc.validation_run
                 WHERE validation_month = ?
                """, Integer.class, month);
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                VALUES (?, ?, 'PRE_CONFIRM', 'CREATED')
                RETURNING validation_run_id
                """, Long.class, month, runNo);
    }

    private record TestReference(Long contractId, LocalDate asOfDate, Long userId) {
    }
}
