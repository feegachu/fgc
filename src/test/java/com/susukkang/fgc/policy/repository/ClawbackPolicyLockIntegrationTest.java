package com.susukkang.fgc.policy.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V75: API를 통하지 않는 SQL도 불변 정책과 환수 계산 근거를 바꿀 수 없다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_clawback_lock_722?currentSchema=fgc")
class ClawbackPolicyLockIntegrationTest {
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "ACTIVE", "RETIRED"})
    void locksInsertsUpdatesDeletesAndBothSidesOfReparenting(String targetStatus) {
        long locked = draft();
        long lockedRule = insertRule(locked, 0);
        long editable = draft();
        long editableRule = insertRule(editable, 12);
        jdbc.update("""
                UPDATE fgc.policy_version SET status='APPROVED', approved_at=clock_timestamp(),
                  approved_by=(SELECT user_id FROM fgc.app_user WHERE login_id='gaadmin')
                WHERE policy_version_id=?
                """, locked);
        if (!targetStatus.equals("APPROVED")) {
            jdbc.update("UPDATE fgc.policy_version SET status='ACTIVE' WHERE policy_version_id=?", locked);
        }
        if (targetStatus.equals("RETIRED")) {
            jdbc.update("UPDATE fgc.policy_version SET status='RETIRED' WHERE policy_version_id=?", locked);
        }

        assertLocked(() -> insertRule(locked, 24));
        assertLocked(() -> jdbc.update("UPDATE fgc.clawback_rule SET clawback_rate_pct=50 WHERE clawback_rule_id=?", lockedRule));
        assertLocked(() -> jdbc.update("DELETE FROM fgc.clawback_rule WHERE clawback_rule_id=?", lockedRule));
        assertLocked(() -> jdbc.update("UPDATE fgc.clawback_rule SET policy_version_id=? WHERE clawback_rule_id=?", editable, lockedRule));
        assertLocked(() -> jdbc.update("UPDATE fgc.clawback_rule SET policy_version_id=? WHERE clawback_rule_id=?", locked, editableRule));
        assertThat(jdbc.queryForObject("SELECT clawback_rate_pct::text FROM fgc.clawback_rule WHERE clawback_rule_id=?",
                String.class, lockedRule)).isEqualTo("33.333333");
        assertThat(jdbc.queryForObject("SELECT policy_version_id FROM fgc.clawback_rule WHERE clawback_rule_id=?",
                Long.class, lockedRule)).isEqualTo(locked);
        assertThat(jdbc.queryForObject("SELECT policy_version_id FROM fgc.clawback_rule WHERE clawback_rule_id=?",
                Long.class, editableRule)).isEqualTo(editable);
    }

    @Test
    void allowsDraftInsertUpdateReparentAndDelete() {
        long first = draft(), second = draft();
        long rule = insertRule(first, 0);
        assertThat(jdbc.update("UPDATE fgc.clawback_rule SET clawback_rate_pct=0 WHERE clawback_rule_id=?", rule)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE fgc.clawback_rule SET policy_version_id=? WHERE clawback_rule_id=?", second, rule)).isEqualTo(1);
        assertThat(jdbc.update("DELETE FROM fgc.clawback_rule WHERE clawback_rule_id=?", rule)).isEqualTo(1);
    }

    private static void assertLocked(Runnable mutation) {
        assertThatThrownBy(mutation::run).isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("Rules under locked policy version");
    }

    private long draft() {
        return jdbc.queryForObject("""
                INSERT INTO fgc.policy_version(policy_code,policy_name,policy_type,source_class,
                  version_no,effective_from,status,created_by)
                VALUES (?, '환수 잠금 검증', 'CLAWBACK', 'PROJECT_ASSUMPTION', 1, DATE '2000-01-01', 'DRAFT',
                  (SELECT user_id FROM fgc.app_user WHERE login_id='settle01'))
                RETURNING policy_version_id
                """, Long.class, "LOCK-722-" + UUID.randomUUID());
    }

    private long insertRule(long policy, int from) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.clawback_rule(policy_version_id,elapsed_month_from,elapsed_month_to,
                  clawback_basis_code,clawback_rate_pct)
                VALUES (?, ?, ?, 'PAID_ATTRIBUTED_AMOUNT', 33.333333) RETURNING clawback_rule_id
                """, Long.class, policy, from, from + 11);
    }
}
