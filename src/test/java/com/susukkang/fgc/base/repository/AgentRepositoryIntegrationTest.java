package com.susukkang.fgc.base.repository;

import com.susukkang.fgc.base.dto.AgentRow;
import com.susukkang.fgc.common.code.AgentRankCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 PostgreSQL과 Flyway 스키마에서 설계사 조회 계약을 검증한다. */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AgentRepositoryIntegrationTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 11);
    private static final LocalDate REGISTERED_ON = LocalDate.of(2026, 5, 1);
    private static final LocalDate SUPPORT_END = LocalDate.of(2027, 4, 30);

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @ParameterizedTest(name = "{0}: 직급 문자열 및 조직·경력 필드를 모두 반환한다")
    @EnumSource(AgentRankCode.class)
    void mapsJoinedOrganizationAndAllAgentFields(AgentRankCode rank) {
        String organizationCode = uniqueCode();
        long organizationId = insertOrganization(organizationCode, null);
        String agentCode = uniqueCode();
        long agentId = insertAgent(agentCode, "설계사", organizationId, rank,
                AS_OF.minusMonths(1), AS_OF, "INACTIVE", false);

        Page<AgentRow> result = agentRepository.search(organizationId, agentCode, AS_OF, page(0, 1));

        assertThat(result.getContent()).containsExactly(new AgentRow(
                agentId, agentCode, "설계사", rank.name(), organizationId, organizationCode,
                "조회 테스트 조직", AS_OF.minusMonths(1), AS_OF, "INACTIVE", REGISTERED_ON,
                false, REGISTERED_ON, true, SUPPORT_END, false
        ));
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("직급·경력·등록일이 없으면 null을 유지한다")
    void preservesNullableFields() {
        long organizationId = insertOrganization(uniqueCode(), null);
        String code = uniqueCode();
        long agentId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.agent (
                    organization_id, agent_code, agent_name, appointment_date, agent_status
                ) VALUES (?, ?, '최소 정보 설계사', ?, 'ACTIVE')
                RETURNING agent_id
                """, Long.class, organizationId, code, AS_OF);

        Page<AgentRow> result = agentRepository.search(organizationId, code, AS_OF, page(0, 1));

        assertThat(result.getContent()).singleElement().satisfies(row -> {
            assertThat(row.agentId()).isEqualTo(agentId);
            assertThat(row.rankCode()).isNull();
            assertThat(row.terminationDate()).isNull();
            assertThat(row.latestRegistrationDate()).isNull();
            assertThat(row.priorThreeYearExperienceYn()).isNull();
            assertThat(row.experienceCheckedOn()).isNull();
            assertThat(row.newcomerSupportEndDate()).isNull();
            assertThat(row.newcomerSupportEligibleYn()).isFalse();
            assertThat(row.activeYn()).isTrue();
        });
    }

    @Test
    @DisplayName("조직 및 위촉·해촉일을 필터링하고 비활성 설계사와 만료된 조직명도 반환한다")
    void filtersOrganizationAndInclusiveDatesWithoutFilteringInactiveRows() {
        long organizationId = insertOrganization(uniqueCode(), null);
        long otherId = insertOrganization(uniqueCode(), null);
        jdbcTemplate.update("""
                UPDATE fgc.organization SET effective_to = ?, active_yn = FALSE
                WHERE organization_id = ?
                """, AS_OF.minusDays(1), organizationId);
        String prefix = uniqueCode();
        insertAgent(prefix + "-A", "위촉 당일", organizationId, AgentRankCode.FC,
                AS_OF, null, "ACTIVE", true);
        insertAgent(prefix + "-B", "해촉 당일", organizationId, AgentRankCode.FC,
                AS_OF.minusMonths(1), AS_OF, "TERMINATED", false);
        insertAgent(prefix + "-C", "당일만 유효", organizationId, AgentRankCode.FC,
                AS_OF, AS_OF, "INACTIVE", false);
        insertAgent(prefix + "-FUTURE", "미래", organizationId, AgentRankCode.FC,
                AS_OF.plusDays(1), null, "ACTIVE", true);
        insertAgent(prefix + "-EXPIRED", "과거", organizationId, AgentRankCode.FC,
                AS_OF.minusMonths(1), AS_OF.minusDays(1), "TERMINATED", false);
        insertAgent(prefix + "-OTHER", "다른 조직", otherId, AgentRankCode.FC,
                AS_OF, null, "ACTIVE", true);

        // 페이지를 채워 countQuery에도 조직·유효기간 조건이 적용되는지 검증한다.
        Page<AgentRow> result = agentRepository.search(organizationId, prefix, AS_OF, page(0, 3));

        assertThat(result.getContent()).extracting(AgentRow::agentCode)
                .containsExactly(prefix + "-A", prefix + "-B", prefix + "-C");
        assertThat(result.getContent()).extracting(AgentRow::activeYn)
                .containsExactly(true, false, false);
        assertThat(result.getContent()).allSatisfy(row -> {
            assertThat(row.organizationId()).isEqualTo(organizationId);
            assertThat(row.organizationName()).isEqualTo("조회 테스트 조직");
        });
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("코드 부분 검색의 대소문자·정렬·페이징·전체 건수를 유지한다")
    void searchesCodeIgnoringCaseAndKeepsSortingPagingAndCount() {
        long organizationId = insertOrganization(uniqueCode(), null);
        String prefix = uniqueCode().toLowerCase(Locale.ROOT);
        for (int index = 21; index >= 1; index--) {
            insertAgent("%s-%02d".formatted(prefix, index), "설계사 " + index,
                    organizationId, AgentRankCode.FC, AS_OF, null, "ACTIVE", true);
        }
        String keyword = prefix.toUpperCase(Locale.ROOT);

        Page<AgentRow> first = agentRepository.search(null, keyword, AS_OF, page(0, 20));
        Page<AgentRow> second = agentRepository.search(null, keyword, AS_OF, page(1, 20));
        Page<AgentRow> beyondLast = agentRepository.search(null, keyword, AS_OF, page(2, 20));

        List<String> expectedFirst = IntStream.rangeClosed(1, 20)
                .mapToObj(index -> "%s-%02d".formatted(prefix, index)).toList();
        assertThat(first.getContent()).extracting(AgentRow::agentCode)
                .containsExactlyElementsOf(expectedFirst);
        assertThat(first.getTotalElements()).isEqualTo(21);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(second.getContent()).extracting(AgentRow::agentCode).containsExactly(prefix + "-21");
        assertThat(second.getTotalElements()).isEqualTo(21);
        assertThat(beyondLast.getContent()).isEmpty();
        assertThat(beyondLast.getTotalElements()).isEqualTo(21);
    }

    @Test
    void searchesNameIgnoringCase() {
        long organizationId = insertOrganization(uniqueCode(), null);
        String keyword = "Kim-" + UUID.randomUUID().toString().substring(0, 8);
        long id = insertAgent(uniqueCode(), "설계사 " + keyword + " 이름", organizationId,
                AgentRankCode.FC, AS_OF, null, "ACTIVE", true);

        Page<AgentRow> result = agentRepository.search(
                null, keyword.toUpperCase(Locale.ROOT), AS_OF, page(0, 1));

        assertThat(result.getContent()).extracting(AgentRow::agentId).containsExactly(id);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("조직과 검색어가 모두 null이어도 목록·건수 쿼리가 정상 실행된다")
    void handlesNullFiltersAndKeepsDateConditions() {
        long organizationId = insertOrganization(uniqueCode(), null);
        insertAgent(uniqueCode(), "유효", organizationId, AgentRankCode.FC, AS_OF, null, "ACTIVE", true);
        insertAgent(uniqueCode(), "미래", organizationId, AgentRankCode.FC,
                AS_OF.plusDays(1), null, "ACTIVE", true);
        List<Long> expectedIds = jdbcTemplate.queryForList("""
                SELECT agent_id FROM fgc.agent
                WHERE appointment_date <= ?
                  AND (termination_date IS NULL OR termination_date >= ?)
                ORDER BY agent_code
                """, Long.class, AS_OF, AS_OF);

        Page<AgentRow> result = agentRepository.search(null, null, AS_OF, page(0, expectedIds.size()));

        assertThat(result.getContent()).extracting(AgentRow::agentId).containsExactlyElementsOf(expectedIds);
        assertThat(result.getTotalElements()).isEqualTo(expectedIds.size());
    }

    @Test
    void returnsEmptyPageWhenNothingMatches() {
        Page<AgentRow> result = agentRepository.search(null, uniqueCode(), AS_OF, page(0, 20));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getTotalPages()).isZero();
    }

    @ParameterizedTest(name = "{0}: 가장 가까운 조직, 같은 조직에서는 작은 ID를 선택한다")
    @EnumSource(AgentRankCode.class)
    void hierarchyPrefersNearestOrganizationThenLowestId(AgentRankCode rank) {
        long parent = insertOrganization(uniqueCode(), null);
        long child = insertOrganization(uniqueCode(), parent);
        long sibling = insertOrganization(uniqueCode(), parent);
        insertAgent(uniqueCode(), "상위 조직", parent, rank, AS_OF, null, "ACTIVE", true);
        insertAgent(uniqueCode(), "형제 조직", sibling, rank, AS_OF, null, "ACTIVE", true);
        long expected = insertAgent(uniqueCode(), "가까운 조직 첫 설계사", child,
                rank, AS_OF, null, "ACTIVE", true);
        insertAgent(uniqueCode(), "가까운 조직 두 번째 설계사", child,
                rank, AS_OF, null, "ACTIVE", true);

        assertThat(agentRepository.findActiveAgentIdFromOrganizationHierarchy(child, rank, AS_OF))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("부적격 설계사를 제외하고 상위 조직에서 위촉·해촉 경계일의 활성 설계사를 찾는다")
    void hierarchySkipsIneligibleAgentsAndSearchesAncestors() {
        long root = insertOrganization(uniqueCode(), null);
        long parent = insertOrganization(uniqueCode(), root);
        long child = insertOrganization(uniqueCode(), parent);
        AgentRankCode rank = AgentRankCode.TEAM_LEADER;
        insertAgent(uniqueCode(), "최상위", root, rank, AS_OF, null, "ACTIVE", true);
        long expected = insertAgent(uniqueCode(), "상위 조직 유효", parent,
                rank, AS_OF, AS_OF, "ACTIVE", true);
        insertAgent(uniqueCode(), "비활동 상태", child, rank, AS_OF, null, "INACTIVE", true);
        insertAgent(uniqueCode(), "해촉 상태", child, rank, AS_OF, null, "TERMINATED", true);
        insertAgent(uniqueCode(), "비활성", child, rank, AS_OF, null, "ACTIVE", false);
        insertAgent(uniqueCode(), "미래 위촉", child, rank, AS_OF.plusDays(1), null, "ACTIVE", true);
        insertAgent(uniqueCode(), "기간 만료", child, rank,
                AS_OF.minusMonths(1), AS_OF.minusDays(1), "ACTIVE", true);
        insertAgent(uniqueCode(), "다른 직급", child, AgentRankCode.FC, AS_OF, null, "ACTIVE", true);
        jdbcTemplate.update("""
                UPDATE fgc.organization SET active_yn = FALSE, effective_to = ?
                WHERE organization_id = ?
                """, AS_OF.minusDays(1), parent);

        assertThat(agentRepository.findActiveAgentIdFromOrganizationHierarchy(child, rank, AS_OF))
                .isEqualTo(expected);
        assertThat(agentRepository.findActiveAgentIdFromOrganizationHierarchy(
                child, rank, AS_OF.plusDays(1)))
                .isNotEqualTo(expected);
    }

    @Test
    void hierarchyReturnsNullWhenOrganizationOrRankHasNoMatch() {
        long organizationId = insertOrganization(uniqueCode(), null);
        insertAgent(uniqueCode(), "설계사", organizationId, AgentRankCode.FC,
                AS_OF, null, "ACTIVE", true);

        assertThat(agentRepository.findActiveAgentIdFromOrganizationHierarchy(
                organizationId, AgentRankCode.TEAM_LEADER, AS_OF)).isNull();
        assertThat(agentRepository.findActiveAgentIdFromOrganizationHierarchy(
                -1L, AgentRankCode.FC, AS_OF)).isNull();
        assertThat(agentRepository.findActiveAgentIdFromOrganizationHierarchy(
                organizationId, null, AS_OF)).isNull();
    }

    private long insertOrganization(String code, Long parentId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.organization (
                    organization_code, organization_name, organization_type,
                    parent_id, effective_from, active_yn
                ) VALUES (?, '조회 테스트 조직', 'TEAM', ?, DATE '2026-01-01', TRUE)
                RETURNING organization_id
                """, Long.class, code, parentId);
    }

    private long insertAgent(String code, String name, long organizationId, AgentRankCode rank,
                             LocalDate appointmentDate, LocalDate terminationDate,
                             String status, boolean activeYn) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.agent (
                    organization_id, agent_code, agent_name, rank_code,
                    appointment_date, termination_date, agent_status, latest_registration_date,
                    prior_three_year_experience_yn, experience_checked_on,
                    newcomer_support_eligible_yn, newcomer_support_end_date, active_yn
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, TRUE, ?, ?)
                RETURNING agent_id
                """, Long.class, organizationId, code, name, rank.name(), appointmentDate,
                terminationDate, status, REGISTERED_ON, REGISTERED_ON, SUPPORT_END, activeYn);
    }

    private PageRequest page(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "agentCode"));
    }

    private String uniqueCode() {
        return "JPA-AG-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
