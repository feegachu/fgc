package com.susukkang.fgc.policy.entity;

import com.susukkang.fgc.common.code.PolicySourceClass;
import com.susukkang.fgc.common.code.PolicyStatus;
import com.susukkang.fgc.common.code.PolicyType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 정책 버전의 enum·배열 및 DB 기본값 매핑을 검증하는 통합 테스트
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PolicyVersionIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void readsPostgresqlTextArraysAndPolicyEnums() {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.policy_version (
                    policy_code, policy_name, policy_type, source_class,
                    version_no, effective_from, effective_to, status,
                    regulation_refs, source_refs
                ) VALUES (?, '정책 매핑 테스트', 'SCHEDULE_ELIGIBILITY', 'PROJECT_ASSUMPTION',
                          2, DATE '2026-01-01', DATE '2026-12-31', 'DRAFT',
                          ARRAY['규정 제1조', '규정, 부칙'], ARRAY['SRC-001', 'SRC-002'])
                RETURNING policy_version_id
                """, Long.class, "TEST-" + UUID.randomUUID());

        PolicyVersion policy = entityManager.find(PolicyVersion.class, id);

        assertThat(policy.getPolicyType()).isEqualTo(PolicyType.SCHEDULE_ELIGIBILITY);
        assertThat(policy.getSourceClass()).isEqualTo(PolicySourceClass.PROJECT_ASSUMPTION);
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.DRAFT);
        assertThat(policy.getVersionNo()).isEqualTo(2);
        assertThat(policy.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(policy.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(policy.getRegulationRefs()).containsExactly("규정 제1조", "규정, 부칙");
        assertThat(policy.getSourceRefs()).containsExactly("SRC-001", "SRC-002");
    }

    @Test
    void readsDatabaseDefaultsAndNullableApprovalFields() {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.policy_version (
                    policy_code, policy_name, policy_type, source_class,
                    version_no, effective_from, status
                ) VALUES (?, '최소 정보 정책', 'CURRENT_COMMISSION', 'INSURER_RULE',
                          1, DATE '2026-01-01', 'DRAFT')
                RETURNING policy_version_id
                """, Long.class, "TEST-" + UUID.randomUUID());

        PolicyVersion policy = entityManager.find(PolicyVersion.class, id);

        assertThat(policy.getRegulationRefs()).isEmpty();
        assertThat(policy.getSourceRefs()).isEmpty();
        assertThat(policy.getEffectiveTo()).isNull();
        assertThat(policy.getCreatedBy()).isNull();
        assertThat(policy.getApprovedBy()).isNull();
        assertThat(policy.getApprovedAt()).isNull();
        assertThat(policy.getCreatedAt()).isNotNull();
        assertThat(policy.getUpdatedAt()).isNotNull();
    }
}
