package com.susukkang.fgc.policy.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V52(#534): 분급 룰셋 테이블 제약과 정책버전 잠금. */
class FeeRegimeRulesetMigrationIntegrationTest {
    private static final String URL = "jdbc:tc:postgresql:17:///fgc_fee_regime_ruleset";

    @Test
    void enforcesConstraintsAndPolicyLock() throws Exception {
        try (var connection = DriverManager.getConnection(URL, "fgc", "fgc_test_only")) {
            Flyway.configure().dataSource(URL, "fgc", "fgc_test_only").schemas("fgc")
                    .locations("classpath:db/migration", "classpath:db/demo").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));

            long policyId = jdbc.queryForObject("""
                    INSERT INTO fgc.policy_version (policy_code, policy_name, policy_type, source_class,
                        version_no, effective_from, status)
                    VALUES ('TEST-FOUR-YEAR', '4년 분급 테스트', 'FOUR_YEAR_COMMISSION', 'PROJECT_ASSUMPTION',
                            1, DATE '2027-01-01', 'DRAFT')
                    RETURNING policy_version_id
                    """, Long.class);

            long rulesetId = insertRuleset(jdbc, policyId, "FOUR_YEAR_2027", 48, null, null);
            jdbc.update("""
                    INSERT INTO fgc.fee_regime_ruleset_limit (fee_regime_ruleset_id, limit_code, limit_pct)
                    VALUES (?, 'UPFRONT_TOTAL', 1)
                    """, rulesetId);
            jdbc.update("""
                    INSERT INTO fgc.fee_regime_transition_rule (fee_regime_ruleset_id, transition_code, applies_from)
                    VALUES (?, 'TEST-TRANSITION', DATE '2027-01-01')
                    """, rulesetId);

            // 체계 코드·한도 코드·장기유지 구간 검증
            assertThatThrownBy(() -> insertRuleset(jdbc, policyId, "CURRENT", 48, null, null))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO fgc.fee_regime_ruleset_limit (fee_regime_ruleset_id, limit_code, limit_pct)
                    VALUES (?, 'FIXED_19', 19)
                    """, rulesetId)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertRuleset(jdbc, policyId, "SEVEN_YEAR_2029", 84, 61, 90))
                    .isInstanceOf(DataIntegrityViolationException.class);
            // 같은 정책·체계·기초서류 버전에서 구간이 겹치는 룰셋은 거부
            assertThatThrownBy(() -> insertRuleset(jdbc, policyId, "FOUR_YEAR_2027", 48, null, null))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // 작성/변경일 구간만 다른 룰셋은 등록되고, 구간이 겹치면 거부된다
            insertRulesetWithDocumentPeriod(jdbc, policyId, "2026-01-01", "2026-06-30");
            insertRulesetWithDocumentPeriod(jdbc, policyId, "2026-07-01", "2026-12-31");
            assertThatThrownBy(() -> insertRulesetWithDocumentPeriod(jdbc, policyId, "2026-06-01", "2026-07-31"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // 승인된 정책 아래 룰셋·한도·경과조치는 변경 불가
            jdbc.update("""
                    UPDATE fgc.policy_version
                       SET status = 'APPROVED', approved_at = now(),
                           created_by = (SELECT min(user_id) FROM fgc.app_user),
                           approved_by = (SELECT max(user_id) FROM fgc.app_user)
                     WHERE policy_version_id = ?
                    """, policyId);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE fgc.fee_regime_ruleset SET monthly_rate_pct = 3 WHERE fee_regime_ruleset_id = ?", rulesetId))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM fgc.fee_regime_ruleset_limit WHERE fee_regime_ruleset_id = ?", rulesetId))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM fgc.fee_regime_transition_rule WHERE fee_regime_ruleset_id = ?", rulesetId))
                    .hasMessageContaining("immutable");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.fee_regime_ruleset", Integer.class)).isEqualTo(3);
        }
    }

    private void insertRulesetWithDocumentPeriod(JdbcTemplate jdbc, long policyId, String from, String to) {
        jdbc.update("""
                INSERT INTO fgc.fee_regime_ruleset (policy_version_id, fee_regime_code, sales_start_date_from,
                    basic_document_date_from, basic_document_date_to, total_installments, monthly_rate_pct)
                VALUES (?, 'SEVEN_YEAR_2029', DATE '2029-01-01', ?::date, ?::date, 84, 2)
                """, policyId, from, to);
    }

    private long insertRuleset(JdbcTemplate jdbc, long policyId, String regime, int installments,
                               Integer ltFrom, Integer ltTo) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.fee_regime_ruleset (policy_version_id, fee_regime_code, sales_start_date_from,
                    total_installments, monthly_rate_pct, long_term_maintenance_from, long_term_maintenance_to)
                VALUES (?, ?, DATE '2027-01-01', ?, 2, ?, ?)
                RETURNING fee_regime_ruleset_id
                """, Long.class, policyId, regime, installments, ltFrom, ltTo);
    }
}
