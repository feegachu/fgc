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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "FGC-FGL02-202601-0001, CLEAR, NOT_APPLICABLE",
            "FGC-FGL01-202601-0001, CLEAR, NOT_APPLICABLE",
            "FGC-FGL02-202603-0475, CANDIDATE, CURRENT_EXCESS",
            "FGC-FGL01-202603-0475, CANDIDATE, CURRENT_EXCESS"})
    void verifiesOldAndPostEffectiveDemoContracts(String contractNo, String status, String candidateType) {
        Long contractId = jdbcTemplate.queryForObject("SELECT contract_id FROM fgc.insurance_contract WHERE contract_no=?", Long.class, contractNo);
        var result = arbitrageService.checkInExistingRun(insertValidationRun(), contractId, LocalDate.of(2026, 8, 31));
        assertThat(result.getResultStatus().name()).isEqualTo(status);
        assertThat(result.getCalculationSnapshot()).contains(candidateType, "2026-03-01");
        Long cases = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fgc.exception_case WHERE source_entity_type='ARBITRAGE_CHECK' AND source_entity_id=?", Long.class, result.getArbitrageCheckId().toString());
        assertThat(cases).isEqualTo("CLEAR".equals(status) ? 0L : 1L);
    }

    @Test
    void missingActivePolicyPersistsReviewInsteadOfSilentlyApplyingFallback() {
        jdbcTemplate.update("UPDATE fgc.policy_version SET status='RETIRED' WHERE policy_type='ARBITRAGE' AND status='ACTIVE'");
        Long id = jdbcTemplate.queryForObject("SELECT contract_id FROM fgc.insurance_contract WHERE contract_no='FGC-FGL02-202603-0475'", Long.class);
        var result = arbitrageService.checkInExistingRun(insertValidationRun(), id, LocalDate.of(2026, 8, 31));
        assertThat(result.getResultStatus().name()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.getCalculationSnapshot()).contains("정책이 없습니다");
        assertThat(jdbcTemplate.queryForObject("SELECT exception_type FROM fgc.exception_case WHERE source_entity_type='ARBITRAGE_CHECK' AND source_entity_id=?",
                String.class, result.getArbitrageCheckId().toString())).isEqualTo("POLICY_MISSING");
        var exception = jdbcTemplate.queryForMap("SELECT exception_case_id,exception_key,reason_code FROM fgc.exception_case WHERE source_entity_type='ARBITRAGE_CHECK' AND source_entity_id=?",
                result.getArbitrageCheckId().toString());
        assertThat(exception.get("reason_code")).isEqualTo("POLICY_MISSING");
        assertThat(exception.get("exception_key").toString()).startsWith("POLICY_MISSING:").endsWith(":POLICY_MISSING");
        var repeated = arbitrageService.checkInExistingRun(result.getValidationRunId(), id, LocalDate.of(2026, 8, 31));
        assertThat(repeated.getArbitrageCheckId()).isEqualTo(result.getArbitrageCheckId());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fgc.exception_occurrence WHERE exception_case_id=?", Long.class,
                exception.get("exception_case_id"))).isEqualTo(1L);
    }

    @Test
    void seedPolicyUsesOneEffectiveDateAndNoUnresolvedChannelDescription() {
        var policy = jdbcTemplate.queryForMap("""
                SELECT pp.description,pp.parameter_value #>> '{}' AS effective_date
                FROM fgc.policy_parameter pp JOIN fgc.policy_version pv USING(policy_version_id)
                WHERE pv.policy_type='ARBITRAGE' AND pv.status='ACTIVE'
                  AND pp.parameter_key='ARBITRAGE_EFFECTIVE_CONTRACT_DATE'
                """);
        assertThat(policy.get("effective_date")).isEqualTo("2026-03-01");
        assertThat(policy.get("description")).isEqualTo("GA→FC 판정은 이 날짜 이상 체결된 신계약에 적용한다.");
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
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                SELECT ?, COALESCE(MAX(run_no), 0) + 1, 'PRE_CONFIRM', 'CREATED'
                  FROM fgc.validation_run
                 WHERE validation_month = ?
                RETURNING validation_run_id
                """, Long.class, month, month);
    }

    private record TestReference(Long contractId, LocalDate asOfDate, Long userId) {
    }
}
