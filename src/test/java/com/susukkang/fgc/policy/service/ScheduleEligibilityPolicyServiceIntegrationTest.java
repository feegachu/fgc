package com.susukkang.fgc.policy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FUN-015 SCHEDULE_ELIGIBILITY 정책 해석. 계약일로 버전을 고르고(운영정책서 제49조) 정책이 없거나 모호하거나
 * 파라미터가 레지스트리와 다르면 통과시키지 않는다. 테스트 트랜잭션은 롤백되어 공유 DB에 정책을 남기지 않는다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ScheduleEligibilityPolicyServiceIntegrationTest {

    private static final Map<String, String> REGISTERED = Map.of(
            "STATUS_MONTH_RULE", "PAY_IF_ACTIVE_ON_DUE_DATE",
            "PRORATION_RULE", "NO_PRORATION",
            "REVIVAL_BACKFILL_RULE", "NO_RETROACTIVE");

    @Autowired
    private JdbcTemplate jdbc;

    private ScheduleEligibilityPolicyService service;
    private long writer;
    private long approver;

    @BeforeEach
    void setUp() {
        service = new ScheduleEligibilityPolicyService(jdbc, new ObjectMapper());
        writer = jdbc.queryForObject("SELECT user_id FROM fgc.app_user WHERE login_id = 'settle01'", Long.class);
        approver = jdbc.queryForObject("SELECT user_id FROM fgc.app_user WHERE login_id = 'gaadmin'", Long.class);
    }

    @Test
    void resolvesTheActivePolicyThatAppliesOnTheContractDate() {
        Long demoPolicy = jdbc.queryForObject("""
                SELECT policy_version_id FROM fgc.policy_version
                 WHERE policy_code = 'STATUS-MONTH-RULE-2026' AND status = 'ACTIVE'
                """, Long.class);

        assertThat(service.resolve(LocalDate.of(2026, 8, 1))).isEqualTo(demoPolicy);
    }

    @Test
    void contractDateChoosesBetweenSucceededVersions() {
        long first = activePolicy("P648-SUCCESSION", 1, "2006-01-01", "2007-12-31", REGISTERED);
        long second = activePolicy("P648-SUCCESSION", 2, "2007-01-01", "2007-12-31", REGISTERED);
        jdbc.update("UPDATE fgc.policy_version SET status = 'RETIRED' WHERE policy_version_id = ?", first);
        jdbc.update("""
                INSERT INTO fgc.policy_succession(previous_policy_version_id, successor_policy_version_id,
                                                  cutover_date, activated_by, activated_at)
                VALUES (?, ?, DATE '2007-01-01', ?, TIMESTAMPTZ '2006-06-01 09:00+09')
                """, first, second, approver);

        assertThat(service.resolve(LocalDate.of(2006, 12, 31))).isEqualTo(first);
        assertThat(service.resolve(LocalDate.of(2007, 1, 1))).isEqualTo(second);
    }

    @Test
    void refusesWhenNoPolicyAppliesOnTheContractDate() {
        assertPolicyMissing(LocalDate.of(2000, 1, 1));
    }

    @Test
    void refusesPoliciesWhoseParametersDifferFromTheRegistry() {
        Map<String, String> missingKey = new HashMap<>(REGISTERED);
        missingKey.remove("REVIVAL_BACKFILL_RULE");
        Map<String, String> unsupportedValue = new HashMap<>(REGISTERED);
        unsupportedValue.put("STATUS_MONTH_RULE", "END_OF_MONTH_STATUS");
        Map<String, String> unknownKey = new HashMap<>(REGISTERED);
        unknownKey.put("FREE_CONDITION", "status == 'ACTIVE'");
        activePolicy("P648-MISSING-KEY", 1, "2002-01-01", "2002-12-31", missingKey);
        activePolicy("P648-UNSUPPORTED", 1, "2004-01-01", "2004-12-31", unsupportedValue);
        activePolicy("P648-UNKNOWN-KEY", 1, "2005-01-01", "2005-12-31", unknownKey);

        assertPolicyMissing(LocalDate.of(2002, 6, 1));
        assertPolicyMissing(LocalDate.of(2004, 6, 1));
        assertPolicyMissing(LocalDate.of(2005, 6, 1));
    }

    @Test
    void refusesToPickOneOfOverlappingPolicies() {
        long only = activePolicy("P648-OVERLAP-A", 1, "2003-01-01", "2003-12-31", REGISTERED);
        activePolicy("P648-OVERLAP-B", 1, "2003-06-01", "2003-12-31", REGISTERED);

        assertThat(service.resolve(LocalDate.of(2003, 3, 1))).isEqualTo(only);
        assertPolicyMissing(LocalDate.of(2003, 7, 1));
    }

    private void assertPolicyMissing(LocalDate contractDate) {
        assertThatThrownBy(() -> service.resolve(contractDate))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_POLICY_MISSING));
    }

    /** 1차 시드처럼 DRAFT에 파라미터를 넣은 뒤 승인·활성화한다(DB 생명주기 가드가 허용하는 경로). */
    private long activePolicy(String code, int versionNo, String from, String to, Map<String, String> parameters) {
        Long id = jdbc.queryForObject("""
                INSERT INTO fgc.policy_version(policy_code, policy_name, policy_type, source_class, version_no,
                                               effective_from, effective_to, status, regulation_refs, source_refs,
                                               created_by)
                VALUES (?, 'FUN-015 정책 해석 시험', 'SCHEDULE_ELIGIBILITY', 'PROJECT_ASSUMPTION', ?,
                        ?::date, ?::date, 'DRAFT', ARRAY['REG-03'], ARRAY[]::text[], ?)
                RETURNING policy_version_id
                """, Long.class, code, versionNo, from, to, writer);
        parameters.forEach((key, value) -> jdbc.update("""
                INSERT INTO fgc.policy_parameter(policy_version_id, parameter_key, parameter_value)
                VALUES (?, ?, to_jsonb(?::text))
                """, id, key, value));
        jdbc.update("""
                UPDATE fgc.policy_version
                   SET status = 'APPROVED', approved_by = ?, approved_at = now(), approval_evidence_ref = 'P648 시험'
                 WHERE policy_version_id = ?
                """, approver, id);
        jdbc.update("UPDATE fgc.policy_version SET status = 'ACTIVE' WHERE policy_version_id = ?", id);
        return id;
    }
}
