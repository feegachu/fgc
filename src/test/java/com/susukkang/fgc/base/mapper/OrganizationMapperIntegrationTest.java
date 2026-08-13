package com.susukkang.fgc.base.mapper;

import com.susukkang.fgc.base.dto.OrganizationRow;
import com.susukkang.fgc.base.dto.OrganizationSearchCriteria;
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
class OrganizationMapperIntegrationTest {

    @Autowired
    private OrganizationMapper organizationMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void filtersByEffectivePeriodButIncludesInactiveOrganizations() {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long parentId = insertOrganization(
                "PARENT-" + suffix, "테스트 상위조직", "GA", null,
                LocalDate.of(2026, 1, 1), null, true
        );
        String prefix = "CHILD-" + suffix;
        insertOrganization(prefix + "-ACTIVE", "유효 활성 조직", "BRANCH", parentId,
                LocalDate.of(2026, 8, 11), null, true);
        insertOrganization(prefix + "-INACTIVE", "유효 비활성 조직", "TEAM", parentId,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 11), false);
        insertOrganization(prefix + "-FUTURE", "미래 조직", "TEAM", parentId,
                LocalDate.of(2026, 8, 12), null, true);
        insertOrganization(prefix + "-EXPIRED", "만료 조직", "TEAM", parentId,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 10), true);

        OrganizationSearchCriteria criteria = new OrganizationSearchCriteria(prefix, asOf);
        List<OrganizationRow> rows = organizationMapper.selectOrganizations(criteria, 0, 20);

        assertThat(rows).extracting(OrganizationRow::organizationCode)
                .containsExactly(prefix + "-ACTIVE", prefix + "-INACTIVE");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.parentId()).isEqualTo(parentId);
            assertThat(row.parentName()).isEqualTo("테스트 상위조직");
        });
        assertThat(rows.get(1).activeYn()).isFalse();
        assertThat(organizationMapper.countOrganizations(criteria)).isEqualTo(2);
    }

    @Test
    void searchesCodeOrNameCaseInsensitivelyAndAppliesStablePaging() {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "page-" + suffix;
        for (int index = 21; index >= 1; index--) {
            insertOrganization(
                    "%s-%02d".formatted(prefix, index),
                    "조직 %02d".formatted(index),
                    "TEAM",
                    null,
                    LocalDate.of(2026, 1, 1),
                    null,
                    true
            );
        }

        OrganizationSearchCriteria criteria = new OrganizationSearchCriteria(prefix.toUpperCase(), asOf);
        List<OrganizationRow> secondPage = organizationMapper.selectOrganizations(criteria, 20, 20);

        assertThat(organizationMapper.countOrganizations(criteria)).isEqualTo(21);
        assertThat(secondPage).singleElement()
                .extracting(OrganizationRow::organizationCode)
                .isEqualTo(prefix + "-21");
    }

    @Test
    void searchesByOrganizationName() {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String uniqueName = "서울테스트본부-" + suffix;
        String code = "NAME-" + suffix;
        insertOrganization(code, uniqueName, "DIVISION", null,
                LocalDate.of(2026, 1, 1), null, true);

        List<OrganizationRow> rows = organizationMapper.selectOrganizations(
                new OrganizationSearchCriteria("테스트본부-" + suffix, asOf), 0, 20
        );

        assertThat(rows).singleElement()
                .extracting(OrganizationRow::organizationCode)
                .isEqualTo(code);
    }

    private long insertOrganization(
            String code,
            String name,
            String type,
            Long parentId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            boolean activeYn
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.organization (
                    organization_code,
                    organization_name,
                    organization_type,
                    parent_id,
                    effective_from,
                    effective_to,
                    active_yn
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING organization_id
                """,
                Long.class,
                code,
                name,
                type,
                parentId,
                effectiveFrom,
                effectiveTo,
                activeYn
        );
    }
}
