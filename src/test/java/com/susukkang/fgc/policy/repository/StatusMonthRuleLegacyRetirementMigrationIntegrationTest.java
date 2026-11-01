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
 * V77이 데모 V3처럼 승계 기록 없이 폐기하므로, 같은 시작일(2026-01-01)로 다시 낸 판이 기존 계약까지 판단할 수 있다.
 * 그 복구 절차는 실제 정책 API로 {@code StatusMonthRuleLegacyReissueIntegrationTest}가 확인한다.
 */
class StatusMonthRuleLegacyRetirementMigrationIntegrationTest {
    private static final String URL = "jdbc:tc:postgresql:17:///fgc_status_month_rule_legacy";
    private static final LocalDate EXISTING_CONTRACT = LocalDate.of(2026, 3, 1);

    @Test
    void retiresTheLegacyVersionWithoutSuccession() throws Exception {
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
}
