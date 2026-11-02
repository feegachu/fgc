package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.OrganizationResponse;
import com.susukkang.fgc.base.dto.OrganizationRow;
import com.susukkang.fgc.base.dto.OrganizationSearchCriteria;
import com.susukkang.fgc.base.repository.OrganizationRepository;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.web.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceImplTest {

    @Mock
    private OrganizationRepository organizationRepository;

    private OrganizationServiceImpl organizationService;

    @BeforeEach
    void setUp() {
        organizationService = new OrganizationServiceImpl(organizationRepository);
    }

    @Test
    @DisplayName("검색어 공백을 제거하고 부모 이름·유형명·페이징 정보를 응답으로 변환한다")
    void trimsKeywordMapsLabelsAndBuildsPageResponse() {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        OrganizationSearchCriteria input = new OrganizationSearchCriteria("  seoul  ", asOf);
        OrganizationRow row = new OrganizationRow(
                11L, "FGC-BR-SEOUL", "서울지사", "BRANCH", 1L, "FGC 대표 GA",
                LocalDate.of(2026, 1, 1), null, false
        );
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        PageRequest expectedPage = PageRequest.of(1, 20, Sort.by("organizationCode"));
        when(organizationRepository.search(eq("seoul"), eq(asOf), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), expectedPage, 21));

        PageResponse<OrganizationResponse> result =
                organizationService.search(input, 2, 20);

        verify(organizationRepository).search(eq("seoul"), eq(asOf), pageableCaptor.capture());
        // API의 2페이지가 JPA의 0-based 1페이지로 전달되는지 확인한다.
        assertThat(pageableCaptor.getValue()).isEqualTo(expectedPage);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.sort()).isEqualTo("organizationCode,asc");
        assertThat(result.content()).containsExactly(new OrganizationResponse(
                11L, "FGC-BR-SEOUL", "서울지사", "BRANCH", "지사", 1L, "FGC 대표 GA",
                LocalDate.of(2026, 1, 1), null, false
        ));
    }

    @ParameterizedTest
    @DisplayName("빈 검색어는 null로 전달하고 조회 결과가 없으면 빈 응답을 반환한다")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void convertsBlankKeywordToNullAndKeepsEmptyResponse(String keyword) {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        PageRequest expectedPage = PageRequest.of(0, 20, Sort.by("organizationCode"));
        when(organizationRepository.search(isNull(), eq(asOf), any(Pageable.class)))
                .thenReturn(Page.empty(expectedPage));

        PageResponse<OrganizationResponse> result = organizationService.search(
                new OrganizationSearchCriteria(keyword, asOf), 1, 20);

        verify(organizationRepository).search(isNull(), eq(asOf), eq(expectedPage));
        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    @DisplayName("마지막 페이지를 초과해도 요청 페이지 번호와 전체 건수를 유지한다")
    void keepsRequestedPageAndTotalWhenPageIsBeyondLastResult() {
        OrganizationSearchCriteria criteria = criteria();
        PageRequest requestedPage = PageRequest.of(2, 20, Sort.by("organizationCode"));
        when(organizationRepository.search(isNull(), eq(criteria.asOf()), eq(requestedPage)))
                .thenReturn(new PageImpl<>(List.of(), requestedPage, 21));

        PageResponse<OrganizationResponse> result = organizationService.search(criteria, 3, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isEqualTo(3);
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(2);
    }

    @ParameterizedTest(name = "page={0}, size={1}: {2} 오류")
    @DisplayName("잘못된 페이징 값은 DB 조회 전에 거절한다")
    @CsvSource({
            "0, 20, page",
            "-1, 20, page",
            "1, 0, size",
            "1, 101, size",
            "2147483647, 100, page"
    })
    void rejectsInvalidPagingBeforeQueryingRepository(int page, int size, String field) {
        assertThatThrownBy(() -> organizationService.search(criteria(), page, size))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo(field);
        verifyNoInteractions(organizationRepository);
    }

    private OrganizationSearchCriteria criteria() {
        return new OrganizationSearchCriteria(null, LocalDate.of(2026, 8, 11));
    }
}
