package com.susukkang.fgc.base.repository;

import com.susukkang.fgc.base.code.OrganizationType;
import com.susukkang.fgc.base.dto.OrganizationRow;
import com.susukkang.fgc.base.entity.Organization;
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

/**
 * 설명 : 실제 PostgreSQL과 Flyway 스키마에서 조직 조회 계약을 검증한다.
 * 테스트 데이터는 각 테스트의 트랜잭션이 끝날 때 롤백된다.
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrganizationRepositoryIntegrationTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 11);

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @ParameterizedTest(name = "{0}: 엔티티 enum과 조회 DTO의 문자열 매핑")
    @EnumSource(OrganizationType.class)
    void mapsEntityAndProjectionIncludingAllOrganizationTypes(OrganizationType type) {
        String code = uniqueCode();
        LocalDate from = AS_OF.minusMonths(1);
        LocalDate to = AS_OF.plusMonths(1);
        long id = insertOrganization(code, "유형 매핑 조직", type.name(), null, from, to, false);

        Organization entity = organizationRepository.findById(id).orElseThrow();
        Page<OrganizationRow> result = organizationRepository.search(code, AS_OF, pageRequest(0, 20));

        assertThat(entity.getOrganizationId()).isEqualTo(id);
        assertThat(entity.getOrganizationCode()).isEqualTo(code);
        assertThat(entity.getOrganizationName()).isEqualTo("유형 매핑 조직");
        assertThat(entity.getOrganizationType()).isEqualTo(type);
        assertThat(entity.getParentId()).isNull();
        assertThat(entity.getEffectiveFrom()).isEqualTo(from);
        assertThat(entity.getEffectiveTo()).isEqualTo(to);
        assertThat(entity.isActiveYn()).isFalse();
        // 상위 조직이 없는 행도 LEFT JOIN 결과에서 누락되면 안 된다.
        assertThat(result.getContent()).containsExactly(new OrganizationRow(
                id, code, "유형 매핑 조직", type.name(), null, null, from, to, false));
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("시작일·종료일을 포함하고 비활성 조직 및 만료된 부모의 이름도 반환한다")
    void filtersByEffectivePeriodWithoutFilteringInactiveOrganizationsOrTheirParents() {
        long parentId = insertOrganization(uniqueCode(), "만료된 비활성 부모", "GA", null,
                AS_OF.minusYears(2), AS_OF.minusYears(1), false);
        String prefix = uniqueCode();
        insertOrganization(prefix + "-A", "시작일인 조직", "BRANCH", parentId,
                AS_OF, null, true);
        insertOrganization(prefix + "-B", "종료일인 비활성 조직", "TEAM", parentId,
                AS_OF.minusMonths(1), AS_OF, false);
        insertOrganization(prefix + "-C", "하루만 유효한 조직", "HQ", parentId,
                AS_OF, AS_OF, true);
        insertOrganization(prefix + "-FUTURE", "미래 조직", "TEAM", parentId,
                AS_OF.plusDays(1), null, true);
        insertOrganization(prefix + "-EXPIRED", "만료 조직", "TEAM", parentId,
                AS_OF.minusMonths(1), AS_OF.minusDays(1), true);

        Page<OrganizationRow> result = organizationRepository.search(prefix, AS_OF, pageRequest(0, 20));

        assertThat(result.getContent()).extracting(OrganizationRow::organizationCode)
                .containsExactly(prefix + "-A", prefix + "-B", prefix + "-C");
        assertThat(result.getContent()).extracting(OrganizationRow::activeYn)
                .containsExactly(true, false, true);
        assertThat(result.getContent()).allSatisfy(row -> {
            assertThat(row.parentId()).isEqualTo(parentId);
            assertThat(row.parentName()).isEqualTo("만료된 비활성 부모");
        });
        assertThat(result.getContent().getFirst().effectiveTo()).isNull();
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("코드 검색은 대소문자를 구분하지 않으며 정렬·페이징·전체 건수가 일치한다")
    void searchesCodeIgnoringCaseAndKeepsSortingPagingAndFilteredCount() {
        String prefix = uniqueCode().toLowerCase(Locale.ROOT);
        // 삽입 순서와 코드 순서를 반대로 만들어 실제 정렬을 확인한다.
        for (int index = 21; index >= 1; index--) {
            insertOrganization("%s-%02d".formatted(prefix, index), "조직 " + index, "TEAM", null,
                    AS_OF.minusMonths(1), null, true);
        }
        insertOrganization(prefix + "-FUTURE", "미래 조직", "TEAM", null,
                AS_OF.plusDays(1), null, true);
        insertOrganization(prefix + "-EXPIRED", "만료 조직", "TEAM", null,
                AS_OF.minusMonths(1), AS_OF.minusDays(1), true);
        insertOrganization(uniqueCode(), "검색어가 다른 조직", "TEAM", null,
                AS_OF.minusMonths(1), null, true);
        String keyword = prefix.toUpperCase(Locale.ROOT);

        // 첫 페이지를 가득 채워 countQuery도 실제 실행되도록 한다.
        Page<OrganizationRow> first = organizationRepository.search(keyword, AS_OF, pageRequest(0, 20));
        Page<OrganizationRow> second = organizationRepository.search(keyword, AS_OF, pageRequest(1, 20));
        Page<OrganizationRow> beyondLast = organizationRepository.search(keyword, AS_OF, pageRequest(2, 20));

        List<String> expectedFirstPage = IntStream.rangeClosed(1, 20)
                .mapToObj(index -> "%s-%02d".formatted(prefix, index))
                .toList();
        assertThat(first.getContent()).extracting(OrganizationRow::organizationCode)
                .containsExactlyElementsOf(expectedFirstPage);
        assertThat(first.getTotalElements()).isEqualTo(21);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(second.getContent()).extracting(OrganizationRow::organizationCode)
                .containsExactly(prefix + "-21");
        assertThat(second.getTotalElements()).isEqualTo(21);
        assertThat(second.getTotalPages()).isEqualTo(2);
        assertThat(beyondLast.getContent()).isEmpty();
        assertThat(beyondLast.getTotalElements()).isEqualTo(21);
        assertThat(beyondLast.getTotalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("조직명의 일부를 대소문자 구분 없이 검색한다")
    void searchesPartOfOrganizationNameIgnoringCase() {
        String keyword = "서울Life-" + UUID.randomUUID().toString().substring(0, 8);
        String code = uniqueCode();
        insertOrganization(code, "가상 " + keyword + " 본부", "DIVISION", null,
                AS_OF.minusMonths(1), null, true);

        Page<OrganizationRow> result = organizationRepository.search(
                keyword.toUpperCase(Locale.ROOT), AS_OF, pageRequest(0, 1));

        assertThat(result.getContent()).extracting(OrganizationRow::organizationCode)
                .containsExactly(code);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("검색어가 null이어도 기준일 필터와 전체 건수를 유지한다")
    void nullKeywordStillAppliesEffectivePeriod() {
        insertOrganization(uniqueCode(), "유효 활성 조직", "TEAM", null,
                AS_OF, null, true);
        insertOrganization(uniqueCode(), "유효 비활성 조직", "TEAM", null,
                AS_OF.minusMonths(1), AS_OF, false);
        insertOrganization(uniqueCode(), "미래 조직", "TEAM", null,
                AS_OF.plusDays(1), null, true);
        insertOrganization(uniqueCode(), "만료 조직", "TEAM", null,
                AS_OF.minusMonths(1), AS_OF.minusDays(1), true);
        // Flyway 초기 데이터도 있으므로 기존 SQL의 조회 결과와 비교한다.
        List<Long> expectedIds = jdbcTemplate.queryForList("""
                SELECT organization_id
                FROM fgc.organization
                WHERE effective_from <= ?
                  AND (effective_to IS NULL OR effective_to >= ?)
                ORDER BY organization_code
                """, Long.class, AS_OF, AS_OF);

        Page<OrganizationRow> result = organizationRepository.search(
                null, AS_OF, pageRequest(0, expectedIds.size()));

        assertThat(result.getContent()).extracting(OrganizationRow::organizationId)
                .containsExactlyElementsOf(expectedIds);
        assertThat(result.getTotalElements()).isEqualTo(expectedIds.size());
        assertThat(result.getTotalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("일치하는 조직이 없으면 빈 페이지와 전체 건수 0을 반환한다")
    void returnsEmptyPageWhenNothingMatches() {
        Page<OrganizationRow> result = organizationRepository.search(
                "no-match-" + UUID.randomUUID(), AS_OF, pageRequest(0, 20));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getTotalPages()).isZero();
    }

    private long insertOrganization(String code, String name, String type, Long parentId,
                                    LocalDate effectiveFrom, LocalDate effectiveTo, boolean activeYn) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.organization (
                    organization_code, organization_name, organization_type, parent_id,
                    effective_from, effective_to, active_yn
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING organization_id
                """, Long.class, code, name, type, parentId, effectiveFrom, effectiveTo, activeYn);
    }

    private PageRequest pageRequest(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "organizationCode"));
    }

    private String uniqueCode() {
        return "JPA-ORG-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
