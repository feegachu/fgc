package com.susukkang.fgc.policy.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.service.ScheduleEligibilityPolicyService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #648(PR #806 리뷰): db/migration만 적용한 DB에는 V2의 유산 STATUS-MONTH-RULE-2026 v1(작성자·파라미터 없음)이 ACTIVE로 남는다.
 * V77이 데모 V3처럼 승계 기록 없이 폐기하므로, 같은 시작일(2026-01-01)로 다시 낸 판이 기존 계약까지 판단한다.
 */
class StatusMonthRuleLegacyRetirementMigrationIntegrationTest {
    private static final String URL = "jdbc:tc:postgresql:17:///fgc_status_month_rule_legacy";
    private static final LocalDate EXISTING_CONTRACT = LocalDate.of(2026, 3, 1);

    @Test
    void retiresTheLegacyVersionWithoutSuccessionSoAReissuedVersionCoversExistingContracts() throws Exception {
        try (var connection = DriverManager.getConnection(URL, "fgc", "fgc_test_only")) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            ScheduleEligibilityPolicyService policies = new ScheduleEligibilityPolicyService(jdbc, new ObjectMapper());

            migrate("74");
            assertThat(legacyStatus(jdbc)).isEqualTo("ACTIVE");
            assertPolicyMissing(policies);

            migrate(null);
            assertThat(legacyStatus(jdbc)).isEqualTo("RETIRED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.policy_succession", Long.class)).isZero();
            assertPolicyMissing(policies); // 새 판을 내기 전에는 통과시키지 않는다

            long reissued = reissue(jdbc);
            assertThat(policies.resolve(EXISTING_CONTRACT)).isEqualTo(reissued);
        }
    }

    private static void migrate(String target) {
        var config = Flyway.configure().dataSource(URL, "fgc", "fgc_test_only").schemas("fgc")
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        config.load().migrate();
    }

    private static String legacyStatus(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT status FROM fgc.policy_version WHERE policy_code = 'STATUS-MONTH-RULE-2026' AND version_no = 1
                """, String.class);
    }

    private static void assertPolicyMissing(ScheduleEligibilityPolicyService policies) {
        assertThatThrownBy(() -> policies.resolve(EXISTING_CONTRACT))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_POLICY_MISSING));
    }

    /** 운영자가 POL-W01에서 같은 시작일의 새 판을 작성·승인·활성화한 결과. 이 DB에는 사용자가 없어 시험용으로 넣는다. */
    private static long reissue(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO fgc.app_role(role_code, role_name) VALUES ('SETTLEMENT', '정산'), ('GA_ADMIN', 'GA관리자')");
        Long writer = user(jdbc, "p648-writer", "SETTLEMENT");
        Long approver = user(jdbc, "p648-approver", "GA_ADMIN");
        Long id = jdbc.queryForObject("""
                INSERT INTO fgc.policy_version(policy_code, policy_name, policy_type, source_class, version_no,
                                               effective_from, status, regulation_refs, source_refs, created_by)
                VALUES ('STATUS-MONTH-RULE-2026', '월중 실효·부활 시 그 달 수수료 처리 기준', 'SCHEDULE_ELIGIBILITY',
                        'PROJECT_ASSUMPTION', 2, DATE '2026-01-01', 'DRAFT', ARRAY['REG-03','REG-13'], ARRAY[]::text[], ?)
                RETURNING policy_version_id
                """, Long.class, writer);
        jdbc.update("""
                INSERT INTO fgc.policy_parameter(policy_version_id, parameter_key, parameter_value)
                VALUES (?, 'STATUS_MONTH_RULE', '"PAY_IF_ACTIVE_ON_DUE_DATE"'), (?, 'PRORATION_RULE', '"NO_PRORATION"'),
                       (?, 'REVIVAL_BACKFILL_RULE', '"NO_RETROACTIVE"')
                """, id, id, id);
        jdbc.update("""
                UPDATE fgc.policy_version
                   SET status = 'APPROVED', approved_by = ?, approved_at = now(), approval_evidence_ref = 'P648 재발행'
                 WHERE policy_version_id = ?
                """, approver, id);
        jdbc.update("UPDATE fgc.policy_version SET status = 'ACTIVE' WHERE policy_version_id = ?", id);
        return id;
    }

    private static Long user(JdbcTemplate jdbc, String login, String role) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.app_user(login_id, password_hash, user_name, role_id)
                VALUES (?, 'unused', 'FUN-015 시험 사용자', (SELECT role_id FROM fgc.app_role WHERE role_code = ?))
                RETURNING user_id
                """, Long.class, login, role);
    }
}
