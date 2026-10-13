package com.susukkang.fgc.cap.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

/** V44 적용 DB의 승인 정책을 보존하며 새 버전으로 정정하고, 중복 활성 정책은 변경 없이 거절한다. */
class CapGrossPolicyMigrationIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans={false, true})
    void preservesOldRuleItemsAndRejectsAmbiguousActiveVersions(boolean duplicate) throws Exception {
        String url="jdbc:tc:postgresql:17:///fgc_cap478_policy_"+duplicate;
        try (var connection=DriverManager.getConnection(url,"fgc","fgc_test_only")) {
            flyway(url,"44").migrate();
            // #475와 함께 배포될 때 선행 버전은 별도 트랜잭션으로 정상 커밋된다.
            // V46 거절의 롤백 범위만 검사하도록 적용 가능한 선행 데모 버전을 먼저 올린다.
            if (java.util.Arrays.stream(flyway(url,null).info().all()).anyMatch(info ->
                    info.getVersion()!=null && "45.1".equals(info.getVersion().toString()))) {
                flyway(url,"45.1").migrate();
            }
            var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            var originalRuleOrder = activeRuleOrder(jdbc);
            Long maxItem=jdbc.queryForObject("SELECT MAX(cap_rule_item_id) FROM fgc.cap_rule_item",Long.class);
            var original=jdbc.queryForList("SELECT to_jsonb(i)::text FROM fgc.cap_rule_item i ORDER BY cap_rule_item_id",String.class);
            if (duplicate) {
                Long id=jdbc.queryForObject("""
                        INSERT INTO fgc.policy_version(policy_code,policy_name,policy_type,source_class,version_no,
                            effective_from,status,created_by)
                        SELECT policy_code,policy_name,policy_type,source_class,99,effective_from,'DRAFT',created_by
                        FROM fgc.policy_version WHERE policy_type='CAP_1200' AND status='ACTIVE'
                        ORDER BY policy_version_id LIMIT 1 RETURNING policy_version_id
                        """,Long.class);
                jdbc.update("UPDATE fgc.policy_version SET status='APPROVED',approved_at=now(),approved_by=(SELECT user_id FROM fgc.app_user WHERE login_id='gaadmin') WHERE policy_version_id=?",id);
                jdbc.update("UPDATE fgc.policy_version SET status='ACTIVE' WHERE policy_version_id=?",id);
                Long before=jdbc.queryForObject("SELECT COUNT(*) FROM fgc.policy_version",Long.class);
                assertThatThrownBy(() -> flyway(url,null).migrate()).hasStackTraceContaining("duplicate ACTIVE CAP policy");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fgc.policy_version",Long.class)).isEqualTo(before);
            } else {
                flyway(url,null).migrate();
                assertThat(activeRuleOrder(jdbc)).containsExactlyElementsOf(originalRuleOrder);
                assertInsurerPolicyWinner(jdbc, "2026-07-01", "REG-CAP-INS-2021-V2", "0");
                assertInsurerPolicyWinner(jdbc, "2027-01-01", "REG-CAP-INS-2027-V1", "3");
                assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM fgc.cap_rule_item i
                        JOIN fgc.cap_rule_set r ON r.cap_rule_set_id=i.cap_rule_set_id
                        JOIN fgc.policy_version p ON p.policy_version_id=r.policy_version_id
                        JOIN fgc.commission_item c ON c.commission_item_id=i.commission_item_id
                        WHERE p.status='ACTIVE' AND c.item_code='CLAWBACK'
                          AND i.decision_reason NOT LIKE '%지급 누계에서 차감하지%'
                        """,Long.class)).isZero();
                assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM (SELECT policy_code FROM fgc.policy_version
                        WHERE policy_type='CAP_1200' AND status='ACTIVE'
                        GROUP BY policy_code HAVING COUNT(*)<>1) duplicates
                        """,Long.class)).isZero();
            }
            assertThat(jdbc.queryForList("SELECT to_jsonb(i)::text FROM fgc.cap_rule_item i WHERE cap_rule_item_id<=? ORDER BY cap_rule_item_id",
                    String.class,maxItem)).containsExactlyElementsOf(original);
        }
    }

    private java.util.List<String> activeRuleOrder(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT CONCAT(p.policy_code,':',r.payment_stage,':',r.contract_date_from,':',
                              r.contract_date_to,':',r.compliance_deduction_pct)
                FROM fgc.cap_rule_set r JOIN fgc.policy_version p USING(policy_version_id)
                WHERE p.policy_type='CAP_1200' AND p.status='ACTIVE'
                ORDER BY r.cap_rule_set_id
                """, String.class);
    }

    private void assertInsurerPolicyWinner(JdbcTemplate jdbc, String date, String code, String deduction) {
        var winner = jdbc.queryForMap("""
                SELECT p.policy_code,r.compliance_deduction_pct
                FROM fgc.cap_rule_set r JOIN fgc.policy_version p USING(policy_version_id)
                WHERE p.status='ACTIVE' AND r.payment_stage='INSURER_TO_GA'
                  AND r.insurer_id IS NULL AND r.product_group_code IS NULL AND r.channel_code IS NULL
                  AND r.contract_date_from<=? AND (r.contract_date_to IS NULL OR r.contract_date_to>=?)
                ORDER BY r.contract_date_from DESC,r.cap_rule_set_id DESC LIMIT 1
                """, java.time.LocalDate.parse(date), java.time.LocalDate.parse(date));
        assertThat(winner.get("policy_code")).isEqualTo(code);
        assertThat((java.math.BigDecimal)winner.get("compliance_deduction_pct")).isEqualByComparingTo(deduction);
    }

    private Flyway flyway(String url,String target) {
        var config=Flyway.configure().dataSource(url,"fgc","fgc_test_only")
                .schemas("fgc").locations("classpath:db/migration","classpath:db/demo");
        if (target!=null) config.target(target);
        return config.load();
    }
}
