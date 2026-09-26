package com.susukkang.fgc.base.entity;

import com.susukkang.fgc.common.code.AgentRankCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 실제 PostgreSQL 행을 읽어 기존 직급 enum과 설계사 엔티티의 매핑을 검증한다.
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AgentEntityIntegrationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @ParameterizedTest(name = "{0}: 문자열 직급을 기존 AgentRankCode로 조회한다")
    @EnumSource(AgentRankCode.class)
    void mapsRankEnumAndAllDeclaredFields(AgentRankCode rankCode) {
        long organizationId = insertOrganization();
        String code = uniqueCode();
        LocalDate appointmentDate = LocalDate.of(2026, 1, 1);
        LocalDate terminationDate = LocalDate.of(2026, 8, 11);
        LocalDate registrationDate = LocalDate.of(2026, 5, 1);
        LocalDate checkedOn = LocalDate.of(2026, 4, 30);
        LocalDate supportEndDate = LocalDate.of(2027, 4, 30);
        Long agentId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.agent (
                    organization_id, agent_code, agent_name, rank_code,
                    appointment_date, termination_date, agent_status,
                    latest_registration_date, prior_three_year_experience_yn, experience_checked_on,
                    newcomer_support_eligible_yn, newcomer_support_end_date, active_yn
                ) VALUES (?, ?, ?, ?, ?, ?, 'TERMINATED', ?, FALSE, ?, TRUE, ?, FALSE)
                RETURNING agent_id
                """, Long.class, organizationId, code, "JPA 매핑 설계사", rankCode.name(),
                appointmentDate, terminationDate, registrationDate, checkedOn, supportEndDate);

        Agent agent = entityManager.find(Agent.class, agentId);

        assertThat(agent.getAgentId()).isEqualTo(agentId);
        assertThat(agent.getOrganizationId()).isEqualTo(organizationId);
        assertThat(agent.getAgentCode()).isEqualTo(code);
        assertThat(agent.getAgentName()).isEqualTo("JPA 매핑 설계사");
        assertThat(agent.getRankCode()).isEqualTo(rankCode);
        assertThat(agent.getAppointmentDate()).isEqualTo(appointmentDate);
        assertThat(agent.getTerminationDate()).isEqualTo(terminationDate);
        assertThat(agent.getAgentStatus()).isEqualTo("TERMINATED");
        assertThat(agent.getLatestRegistrationDate()).isEqualTo(registrationDate);
        assertThat(agent.getPriorThreeYearExperienceYn()).isFalse();
        assertThat(agent.getExperienceCheckedOn()).isEqualTo(checkedOn);
        assertThat(agent.isNewcomerSupportEligibleYn()).isTrue();
        assertThat(agent.getNewcomerSupportEndDate()).isEqualTo(supportEndDate);
        assertThat(agent.isActiveYn()).isFalse();
    }

    @Test
    @DisplayName("직급·경력 확인값의 null과 DB의 활성·신인 지원 기본값을 보존한다")
    void preservesNullableFieldsAndDatabaseDefaults() {
        long organizationId = insertOrganization();
        Long agentId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.agent (
                    organization_id, agent_code, agent_name, appointment_date, agent_status
                ) VALUES (?, ?, ?, DATE '2026-01-01', 'ACTIVE')
                RETURNING agent_id
                """, Long.class, organizationId, uniqueCode(), "미확인 경력 설계사");

        Agent agent = entityManager.find(Agent.class, agentId);

        assertThat(agent.getRankCode()).isNull();
        assertThat(agent.getPriorThreeYearExperienceYn()).isNull();
        assertThat(agent.getTerminationDate()).isNull();
        assertThat(agent.getLatestRegistrationDate()).isNull();
        assertThat(agent.getExperienceCheckedOn()).isNull();
        assertThat(agent.getNewcomerSupportEndDate()).isNull();
        assertThat(agent.getAgentStatus()).isEqualTo("ACTIVE");
        assertThat(agent.isNewcomerSupportEligibleYn()).isFalse();
        assertThat(agent.isActiveYn()).isTrue();
    }

    private long insertOrganization() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.organization (
                    organization_code, organization_name, organization_type, effective_from
                ) VALUES (?, ?, 'BRANCH', DATE '2026-01-01')
                RETURNING organization_id
                """, Long.class, uniqueCode(), "설계사 매핑 테스트 조직");
    }

    private String uniqueCode() {
        return "JPA-AGENT-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
