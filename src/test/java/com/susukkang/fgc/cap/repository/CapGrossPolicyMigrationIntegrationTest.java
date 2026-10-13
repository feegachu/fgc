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
            var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
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

    private Flyway flyway(String url,String target) {
        var config=Flyway.configure().dataSource(url,"fgc","fgc_test_only")
                .schemas("fgc").locations("classpath:db/migration","classpath:db/demo");
        if (target!=null) config.target(target);
        return config.load();
    }
}
