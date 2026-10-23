package com.susukkang.fgc.policy.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V59(#534): 분급 룰셋 테이블 제약과 정책버전 잠금. */
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

            long sevenPolicyId = insertPolicy(jdbc, "TEST-SEVEN-YEAR", "SEVEN_YEAR_COMMISSION");
            long capPolicyId = insertPolicy(jdbc, "TEST-CAP", "CAP_1200");

            // 룰셋 체계는 소속 정책버전의 체계와 같아야 한다
            assertThatThrownBy(() -> insertRuleset(jdbc, policyId, "SEVEN_YEAR_2029", 84, null, null))
                    .hasMessageContaining("does not match policy type");
            assertThatThrownBy(() -> insertRuleset(jdbc, sevenPolicyId, "FOUR_YEAR_2027", 48, null, null))
                    .hasMessageContaining("does not match policy type");
            assertThatThrownBy(() -> insertRuleset(jdbc, capPolicyId, "FOUR_YEAR_2027", 48, null, null))
                    .hasMessageContaining("does not match policy type");
            // 정책버전에 기초서류 버전이 있으면 룰셋 값과 같아야 한다
            long documentPolicyId = jdbc.queryForObject("""
                    INSERT INTO fgc.policy_version (policy_code, policy_name, policy_type, source_class,
                        version_no, effective_from, status, fee_regime_code, basic_document_version)
                    VALUES ('TEST-DOC', '기초서류 일치 테스트', 'FOUR_YEAR_COMMISSION', 'PROJECT_ASSUMPTION',
                            1, DATE '2027-01-01', 'DRAFT', 'FOUR_YEAR_2027', 'BD-OTHER')
                    RETURNING policy_version_id
                    """, Long.class);
            assertThatThrownBy(() -> insertRuleset(jdbc, documentPolicyId, "FOUR_YEAR_2027", 48, null, null))
                    .hasMessageContaining("does not match basic_document_version");

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
            assertThatThrownBy(() -> insertRuleset(jdbc, sevenPolicyId, "SEVEN_YEAR_2029", 84, 61, 90))
                    .isInstanceOf(DataIntegrityViolationException.class);
            // 체계별 법정 상한: 4년은 48회차 이내·장기유지 없음, 7년은 84회차 이내. 기초서류 버전은 필수
            assertThatThrownBy(() -> insertRulesetWithVersion(jdbc, policyId, "FOUR_YEAR_2027", "BD-NEG-1", 84, null, null))
                    .hasMessageContaining("ck_fee_regime_ruleset_installments");
            assertThatThrownBy(() -> insertRulesetWithVersion(jdbc, policyId, "FOUR_YEAR_2027", "BD-NEG-2", 48, 37, 48))
                    .hasMessageContaining("ck_fee_regime_ruleset_installments");
            assertThatThrownBy(() -> insertRulesetWithVersion(jdbc, sevenPolicyId, "SEVEN_YEAR_2029", "BD-NEG-3", 85, null, null))
                    .hasMessageContaining("ck_fee_regime_ruleset_installments");
            assertThatThrownBy(() -> insertRulesetWithVersion(jdbc, sevenPolicyId, "SEVEN_YEAR_2029", null, 84, null, null))
                    .isInstanceOf(DataIntegrityViolationException.class);
            // 같은 정책·체계·기초서류 버전에서 구간이 겹치는 룰셋은 거부
            assertThatThrownBy(() -> insertRuleset(jdbc, policyId, "FOUR_YEAR_2027", 48, null, null))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // 작성/변경일 구간만 다른 룰셋은 등록되고, 구간이 겹치면 거부된다
            insertRulesetWithDocumentPeriod(jdbc, sevenPolicyId, "2026-01-01", "2026-06-30");
            insertRulesetWithDocumentPeriod(jdbc, sevenPolicyId, "2026-07-01", "2026-12-31");
            assertThatThrownBy(() -> insertRulesetWithDocumentPeriod(jdbc, sevenPolicyId, "2026-06-01", "2026-07-31"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // 룰셋이 달린 정책버전의 체계 값을 어긋나게 바꾸는 것은 거부, 일치하는 값으로의 변경은 허용
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE fgc.policy_version SET policy_type = 'SEVEN_YEAR_COMMISSION' WHERE policy_version_id = ?", policyId))
                    .hasMessageContaining("no longer matches");
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE fgc.policy_version SET fee_regime_code = 'SEVEN_YEAR_2029' WHERE policy_version_id = ?", policyId))
                    .hasMessageContaining("no longer matches");
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE fgc.policy_version SET basic_document_version = 'BD-OTHER' WHERE policy_version_id = ?", policyId))
                    .hasMessageContaining("no longer matches");
            assertThat(jdbc.update("""
                    UPDATE fgc.policy_version SET fee_regime_code = 'FOUR_YEAR_2027', basic_document_version = 'BD-2027-01'
                     WHERE policy_version_id = ?
                    """, policyId)).isEqualTo(1);

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
                INSERT INTO fgc.fee_regime_ruleset (policy_version_id, fee_regime_code, sales_start_date_from, basic_document_version,
                    basic_document_date_from, basic_document_date_to, total_installments, monthly_rate_pct)
                VALUES (?, 'SEVEN_YEAR_2029', DATE '2029-01-01', 'BD-2029-01', ?::date, ?::date, 84, 2)
                """, policyId, from, to);
    }

    private long insertPolicy(JdbcTemplate jdbc, String code, String policyType) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.policy_version (policy_code, policy_name, policy_type, source_class,
                    version_no, effective_from, status)
                VALUES (?, ?, ?, 'PROJECT_ASSUMPTION', 1, DATE '2027-01-01', 'DRAFT')
                RETURNING policy_version_id
                """, Long.class, code, code, policyType);
    }

    private void insertRulesetWithVersion(JdbcTemplate jdbc, long policyId, String regime, String version,
                                          int installments, Integer ltFrom, Integer ltTo) {
        jdbc.update("""
                INSERT INTO fgc.fee_regime_ruleset (policy_version_id, fee_regime_code, sales_start_date_from,
                    basic_document_version, total_installments, monthly_rate_pct,
                    long_term_maintenance_from, long_term_maintenance_to)
                VALUES (?, ?, DATE '2027-01-01', ?, ?, 2, ?, ?)
                """, policyId, regime, version, installments, ltFrom, ltTo);
    }

    private long insertRuleset(JdbcTemplate jdbc, long policyId, String regime, int installments,
                               Integer ltFrom, Integer ltTo) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.fee_regime_ruleset (policy_version_id, fee_regime_code, sales_start_date_from, basic_document_version,
                    total_installments, monthly_rate_pct, long_term_maintenance_from, long_term_maintenance_to)
                VALUES (?, ?, DATE '2027-01-01', 'BD-2027-01', ?, 2, ?, ?)
                RETURNING fee_regime_ruleset_id
                """, Long.class, policyId, regime, installments, ltFrom, ltTo);
    }
}
