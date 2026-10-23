package com.susukkang.fgc.cap.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 이슈 #635 / SRC-065 / 시드명세 §40: V63이 유산 CAP 정책을 폐기해 동일 범위 활성 중복을 0건으로 만든다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CapLegacyPolicyRetirementMigrationIntegrationTest {
    private static final String PASSWORD = "fgc_test_only";
    private static final String OVERLAP_COUNT = """
            SELECT COUNT(*) FROM fgc.cap_rule_set a
            JOIN fgc.policy_version pa ON pa.policy_version_id = a.policy_version_id AND pa.status = 'ACTIVE'
            JOIN fgc.cap_rule_set b ON b.cap_rule_set_id > a.cap_rule_set_id
                 AND b.payment_stage = a.payment_stage
                 AND b.insurer_id IS NOT DISTINCT FROM a.insurer_id
                 AND b.product_group_code IS NOT DISTINCT FROM a.product_group_code
                 AND b.channel_code IS NOT DISTINCT FROM a.channel_code
                 AND a.contract_date_from <= COALESCE(b.contract_date_to, DATE 'infinity')
                 AND b.contract_date_from <= COALESCE(a.contract_date_to, DATE 'infinity')
            JOIN fgc.policy_version pb ON pb.policy_version_id = b.policy_version_id AND pb.status = 'ACTIVE'
            """;
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("fgc_cap635").withUsername("fgc").withPassword(PASSWORD);
    private JdbcTemplate jdbc;
    private long legacyChildCountBefore;
    private long overlapBefore;
    private long legacyBoundaryWinnersBefore;

    @BeforeAll
    void setup() {
        postgres.start();
        flyway(postgres.getJdbcUrl(), "62").migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), "fgc", PASSWORD));
        overlapBefore = jdbc.queryForObject(OVERLAP_COUNT, Long.class);
        legacyChildCountBefore = legacyChildCount();
        legacyBoundaryWinnersBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fgc.policy_version WHERE policy_code = 'REG-CAP-INS-2026-V1' AND status = 'ACTIVE'",
                Long.class);
        flyway(postgres.getJdbcUrl(), null).migrate();
    }

    @AfterAll
    void stop() {
        postgres.stop();
    }

    @Test
    void legacyPolicyOverlappedBeforeCleanupAndIsGoneAfter() {
        assertThat(overlapBefore).isGreaterThan(0L);
        assertThat(legacyBoundaryWinnersBefore).isEqualTo(1L);
        assertThat(jdbc.queryForObject(OVERLAP_COUNT, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM fgc.policy_version
                WHERE policy_code = 'REG-CAP-INS-2026-V1' AND status = 'ACTIVE'""", Long.class)).isZero();
    }

    @Test
    void keepsLegacyHistoryAndChildRows() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM fgc.policy_version
                WHERE policy_code = 'REG-CAP-INS-2026-V1' AND status = 'RETIRED'""", Long.class)).isGreaterThan(0L);
        assertThat(legacyChildCount()).isEqualTo(legacyChildCountBefore);
    }

    @Test
    void selectsOneNonOverlappingRuleSetAtContractBoundaryDates() {
        assertThat(activeInsurerPolicyCodes("2021-01-01")).containsExactly("REG-CAP-INS-2021-V2");
        assertThat(activeInsurerPolicyCodes("2026-12-31")).containsExactly("REG-CAP-INS-2021-V2");
        assertThat(activeInsurerPolicyCodes("2027-01-01")).containsExactly("REG-CAP-INS-2027-V1");
    }

    @Test
    void rollsBackRetirementWhenReplacementRuleSetsDoNotCoverRange() {
        String url = databaseAt62("fgc_cap635_precheck");
        var db = jdbc(url);
        db.update("UPDATE fgc.policy_version SET status = 'RETIRED' WHERE policy_code = 'REG-CAP-INS-2027-V1' AND status = 'ACTIVE'");

        assertThatThrownBy(() -> flyway(url, null).migrate()).hasStackTraceContaining("범위를 채우지 못한다");
        assertRetirementRolledBack(db);
    }

    @Test
    void rollsBackRetirementWhenActiveOverlapRemains() {
        String url = databaseAt62("fgc_cap635_postcheck");
        var db = jdbc(url);
        // 유산 정책과 무관한 GA→설계사 단계에 REG-CAP-GA-2026-V1과 같은 범위·기간의 활성 정책을 하나 더 만든다.
        Long id = db.queryForObject("""
                INSERT INTO fgc.policy_version(policy_code, policy_name, policy_type, source_class, version_no,
                    effective_from, status, created_by)
                VALUES ('TEST-CAP-GA-OVERLAP', '중복 검증용 GA→설계사 룰셋', 'CAP_1200', 'REGULATORY', 1,
                    DATE '2026-07-01', 'DRAFT', (SELECT user_id FROM fgc.app_user WHERE login_id = 'settle01'))
                RETURNING policy_version_id""", Long.class);
        db.update("""
                INSERT INTO fgc.cap_rule_set(policy_version_id, payment_stage, contract_date_from, first_year_months,
                    premium_multiplier, compliance_deduction_pct, refund_addition_condition, warning_usage_pct)
                VALUES (?, 'GA_TO_FC', DATE '2026-07-01', 12, 12.0000, 0.0000, 'STANDARD_DEDUCTION_80', 90.0000)""", id);
        db.update("""
                UPDATE fgc.policy_version SET status = 'APPROVED', approved_at = now(),
                    approved_by = (SELECT user_id FROM fgc.app_user WHERE login_id = 'gaadmin')
                WHERE policy_version_id = ?""", id);
        db.update("UPDATE fgc.policy_version SET status = 'ACTIVE' WHERE policy_version_id = ?", id);

        assertThatThrownBy(() -> flyway(url, null).migrate()).hasStackTraceContaining("활성 CAP 룰셋 중복");
        assertRetirementRolledBack(db);
    }

    // 사전·사후 검사가 RAISE하면 같은 트랜잭션의 폐기 UPDATE도 롤백돼 유산 정책이 ACTIVE로 남고 V63은 적용 이력에 없다.
    private void assertRetirementRolledBack(JdbcTemplate db) {
        assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM fgc.policy_version
                WHERE policy_code = 'REG-CAP-INS-2026-V1' AND status = 'ACTIVE'""", Long.class)).isEqualTo(1L);
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM fgc.flyway_schema_history WHERE version = '63'", Long.class)).isZero();
    }

    private String databaseAt62(String name) {
        jdbc.execute("CREATE DATABASE " + name);
        String url = postgres.getJdbcUrl().replace("/fgc_cap635", "/" + name);
        flyway(url, "62").migrate();
        return url;
    }

    private JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, "fgc", PASSWORD));
    }

    private long legacyChildCount() {
        return jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM fgc.cap_rule_set r JOIN fgc.policy_version p USING (policy_version_id)
                         WHERE p.policy_code = 'REG-CAP-INS-2026-V1')
                     + (SELECT COUNT(*) FROM fgc.cap_rule_item i
                         JOIN fgc.cap_rule_set r USING (cap_rule_set_id)
                         JOIN fgc.policy_version p USING (policy_version_id)
                         WHERE p.policy_code = 'REG-CAP-INS-2026-V1')""", Long.class);
    }

    private java.util.List<String> activeInsurerPolicyCodes(String date) {
        return jdbc.queryForList("""
                SELECT p.policy_code FROM fgc.cap_rule_set r JOIN fgc.policy_version p USING (policy_version_id)
                WHERE p.status = 'ACTIVE' AND r.payment_stage = 'INSURER_TO_GA'
                  AND r.insurer_id IS NULL AND r.product_group_code IS NULL AND r.channel_code IS NULL
                  AND r.contract_date_from <= ?::date AND (r.contract_date_to IS NULL OR r.contract_date_to >= ?::date)
                ORDER BY p.policy_code""", String.class, date, date);
    }

    private Flyway flyway(String url, String target) {
        var config = Flyway.configure().dataSource(url, "fgc", PASSWORD).schemas("fgc")
                .locations("classpath:db/migration", "classpath:db/demo");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }
}
