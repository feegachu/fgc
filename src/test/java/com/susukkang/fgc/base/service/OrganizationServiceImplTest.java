package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.OrganizationRow;
import com.susukkang.fgc.base.dto.OrganizationResponse;
import com.susukkang.fgc.base.dto.OrganizationSearchCriteria;
import com.susukkang.fgc.base.mapper.OrganizationMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.web.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceImplTest {

    @Mock
    private OrganizationMapper organizationMapper;

    private OrganizationServiceImpl organizationService;

    @BeforeEach
    void setUp() {
        organizationService = new OrganizationServiceImpl(organizationMapper);
    }

    @Test
    void trimsKeywordMapsLabelsAndBuildsPageResponse() {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        OrganizationSearchCriteria input = new OrganizationSearchCriteria("  seoul  ", asOf);
        OrganizationRow row = new OrganizationRow(
                11L, "FGC-BR-SEOUL", "서울지사", "BRANCH", 1L, "FGC 대표 GA",
                LocalDate.of(2026, 1, 1), null, false
        );
        ArgumentCaptor<OrganizationSearchCriteria> criteriaCaptor =
                ArgumentCaptor.forClass(OrganizationSearchCriteria.class);

        when(organizationMapper.selectOrganizations(
                any(OrganizationSearchCriteria.class),
                eq(20),
                eq(20)
        )).thenReturn(List.of(row));
        when(organizationMapper.countOrganizations(
                any(OrganizationSearchCriteria.class)
        )).thenReturn(21L);

        PageResponse<OrganizationResponse> result =
                organizationService.search(input, 2, 20);

        verify(organizationMapper).selectOrganizations(criteriaCaptor.capture(),
                eq(20), eq(20));
        assertThat(criteriaCaptor.getValue()).isEqualTo(new OrganizationSearchCriteria("seoul", asOf));
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.sort()).isEqualTo("organizationCode,asc");
        assertThat(result.content()).singleElement().satisfies(response -> {
            assertThat(response.organizationTypeLabel()).isEqualTo("지사");
            assertThat(response.parentName()).isEqualTo("FGC 대표 GA");
            assertThat(response.activeYn()).isFalse();
        });
    }

    @Test
    void convertsBlankKeywordToNull() {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        ArgumentCaptor<OrganizationSearchCriteria> criteriaCaptor =
                ArgumentCaptor.forClass(OrganizationSearchCriteria.class);
        when(organizationMapper.selectOrganizations(
                any(OrganizationSearchCriteria.class),
                eq(0),
                eq(20)
        )).thenReturn(List.of());
        when(organizationMapper.countOrganizations(
                any(OrganizationSearchCriteria.class)
        )).thenReturn(0L);

        organizationService.search(new OrganizationSearchCriteria("   ", asOf), 1, 20);

        verify(organizationMapper).selectOrganizations(criteriaCaptor.capture(),
                eq(0), eq(20));
        assertThat(criteriaCaptor.getValue().keyword()).isNull();
    }

    @Test
    void rejectsPageBelowOne() {
        assertThatThrownBy(() -> organizationService.search(criteria(), 0, 20))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("page");
    }

    @Test
    void rejectsSizeOutsideOneToOneHundred() {
        assertThatThrownBy(() -> organizationService.search(criteria(), 1, 0))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("size");
        assertThatThrownBy(() -> organizationService.search(criteria(), 1, 101))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("size");
    }

    @Test
    void rejectsPageWhenOffsetExceedsIntegerRange() {
        assertThatThrownBy(() -> organizationService.search(criteria(), Integer.MAX_VALUE, 100))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("page");
    }

    private OrganizationSearchCriteria criteria() {
        return new OrganizationSearchCriteria(null, LocalDate.of(2026, 8, 11));
    }
}
