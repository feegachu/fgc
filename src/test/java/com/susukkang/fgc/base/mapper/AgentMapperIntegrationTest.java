package com.susukkang.fgc.base.mapper;

import com.susukkang.fgc.base.dto.AgentRow;
import com.susukkang.fgc.base.dto.AgentSearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AgentMapperIntegrationTest {

    @Autowired
    private AgentMapper agentMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void filtersByOrganizationAndAppointmentPeriodButIncludesInactiveRows() {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long organizationId = insertOrganization("AGENT-ORG-" + suffix, "설계사 테스트 조직");
        long otherOrganizationId = insertOrganization("OTHER-ORG-" + suffix, "다른 조직");
        String prefix = "AGENT-" + suffix;

        insertAgent(prefix + "-ACTIVE", "유효 활동", organizationId,
                LocalDate.of(2026, 1, 1), null, "ACTIVE", true);
        insertAgent(prefix + "-INACTIVE", "유효 비활동", organizationId,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 11), "INACTIVE", false);
        insertAgent(prefix + "-FUTURE", "미래 위촉", organizationId,
                LocalDate.of(2026, 8, 12), null, "ACTIVE", true);
        insertAgent(prefix + "-EXPIRED", "과거 해촉", organizationId,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 10), "TERMINATED", false);
        insertAgent(prefix + "-OTHER", "다른 조직 설계사", otherOrganizationId,
                LocalDate.of(2026, 1, 1), null, "ACTIVE", true);

        AgentSearchCriteria criteria = new AgentSearchCriteria(organizationId, prefix, asOf);
        List<AgentRow> rows = agentMapper.selectAgents(criteria, 0, 20);

        assertThat(rows).extracting(AgentRow::agentCode)
                .containsExactly(prefix + "-ACTIVE", prefix + "-INACTIVE");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.organizationId()).isEqualTo(organizationId);
            assertThat(row.organizationCode()).isEqualTo("AGENT-ORG-" + suffix);
            assertThat(row.organizationName()).isEqualTo("설계사 테스트 조직");
        });
        assertThat(rows.get(1).activeYn()).isFalse();
        assertThat(agentMapper.countAgents(criteria)).isEqualTo(2);
    }

    @Test
    void searchesCodeOrNameCaseInsensitivelyAndAppliesStablePaging() {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long organizationId = insertOrganization("PAGE-ORG-" + suffix, "페이징 조직");
        String prefix = "page-agent-" + suffix;
        for (int index = 21; index >= 1; index--) {
            insertAgent(
                    "%s-%02d".formatted(prefix, index),
                    "설계사 %02d".formatted(index),
                    organizationId,
                    LocalDate.of(2026, 1, 1),
                    null,
                    "ACTIVE",
                    true
            );
        }

        AgentSearchCriteria criteria = new AgentSearchCriteria(null, prefix.toUpperCase(), asOf);
        List<AgentRow> secondPage = agentMapper.selectAgents(criteria, 20, 20);

        assertThat(agentMapper.countAgents(criteria)).isEqualTo(21);
        assertThat(secondPage).singleElement()
                .extracting(AgentRow::agentCode)
                .isEqualTo(prefix + "-21");
    }

    @Test
    void searchesByAgentNameAndMapsNewcomerFields() {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long organizationId = insertOrganization("NAME-ORG-" + suffix, "이름 검색 조직");
        String code = "NAME-AGENT-" + suffix;
        insertAgent(code, "이름검색설계사-" + suffix, organizationId,
                LocalDate.of(2026, 5, 1), null, "ACTIVE", true);

        List<AgentRow> rows = agentMapper.selectAgents(
                new AgentSearchCriteria(null, "검색설계사-" + suffix, asOf), 0, 20
        );

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.agentCode()).isEqualTo(code);
            assertThat(row.rankCode()).isEqualTo("FC");
            assertThat(row.latestRegistrationDate()).isEqualTo(LocalDate.of(2026, 5, 1));
            assertThat(row.priorThreeYearExperienceYn()).isFalse();
            assertThat(row.experienceCheckedOn()).isEqualTo(LocalDate.of(2026, 5, 1));
            assertThat(row.newcomerSupportEligibleYn()).isTrue();
            assertThat(row.newcomerSupportEndDate()).isEqualTo(LocalDate.of(2027, 4, 30));
        });
    }

    private long insertOrganization(String code, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.organization (
                    organization_code,
                    organization_name,
                    organization_type,
                    effective_from,
                    active_yn
                ) VALUES (?, ?, 'BRANCH', DATE '2026-01-01', TRUE)
                RETURNING organization_id
                """, Long.class, code, name);
    }

    private void insertAgent(
            String code,
            String name,
            long organizationId,
            LocalDate appointmentDate,
            LocalDate terminationDate,
            String status,
            boolean activeYn
    ) {
        jdbcTemplate.update("""
                INSERT INTO fgc.agent (
                    organization_id,
                    agent_code,
                    agent_name,
                    rank_code,
                    appointment_date,
                    termination_date,
                    agent_status,
                    latest_registration_date,
                    prior_three_year_experience_yn,
                    experience_checked_on,
                    newcomer_support_eligible_yn,
                    newcomer_support_end_date,
                    active_yn
                ) VALUES (?, ?, ?, 'FC', ?, ?, ?, DATE '2026-05-01', FALSE,
                          DATE '2026-05-01', TRUE, DATE '2027-04-30', ?)
                """,
                organizationId,
                code,
                name,
                appointmentDate,
                terminationDate,
                status,
                activeYn
        );
    }
}
