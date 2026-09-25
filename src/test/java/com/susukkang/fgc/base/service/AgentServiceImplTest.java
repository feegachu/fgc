package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.AgentResponse;
import com.susukkang.fgc.base.dto.AgentRow;
import com.susukkang.fgc.base.dto.AgentSearchCriteria;
import com.susukkang.fgc.base.repository.AgentRepository;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.web.PageResponse;
import org.junit.jupiter.api.BeforeEach;
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
class AgentServiceImplTest {

    @Mock
    private AgentRepository agentRepository;

    private AgentServiceImpl agentService;

    @BeforeEach
    void setUp() {
        agentService = new AgentServiceImpl(agentRepository);
    }

    @Test
    void normalizesCriteriaMapsLabelsAndBuildsPageResponse() {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        AgentSearchCriteria input = new AgentSearchCriteria(11L, "  kim  ", asOf);
        AgentRow row = row("FC", "INACTIVE", false);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        PageRequest expectedPage = PageRequest.of(1, 20, Sort.by("agentCode"));
        when(agentRepository.search(eq(11L), eq("kim"), eq(asOf), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), expectedPage, 21));

        PageResponse<AgentResponse> result = agentService.search(input, 2, 20);

        verify(agentRepository).search(eq(11L), eq("kim"), eq(asOf), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue()).isEqualTo(expectedPage);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.sort()).isEqualTo("agentCode,asc");
        assertThat(result.content()).containsExactly(new AgentResponse(
                101L, "FC-SEOUL-001", "김설계", "FC", "설계사",
                11L, "FGC-BR-SEOUL", "서울지사", LocalDate.of(2026, 5, 1), null,
                "INACTIVE", "비활동", LocalDate.of(2026, 5, 1), false,
                LocalDate.of(2026, 5, 1), true, LocalDate.of(2027, 4, 30), false
        ));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void convertsBlankKeywordToNull(String keyword) {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        PageRequest expectedPage = PageRequest.of(0, 20, Sort.by("agentCode"));
        when(agentRepository.search(isNull(), isNull(), eq(asOf), any(Pageable.class)))
                .thenReturn(Page.empty(expectedPage));

        PageResponse<AgentResponse> result = agentService.search(
                new AgentSearchCriteria(null, keyword, asOf), 1, 20);

        verify(agentRepository).search(isNull(), isNull(), eq(asOf), eq(expectedPage));
        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidOrganizationId(long organizationId) {
        assertThatThrownBy(() -> agentService.search(
                new AgentSearchCriteria(organizationId, null, LocalDate.of(2026, 8, 11)), 1, 20))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("organizationId");
        verifyNoInteractions(agentRepository);
    }

    @ParameterizedTest(name = "page={0}, size={1}: {2} 오류")
    @CsvSource({
            "0, 20, page",
            "-1, 20, page",
            "1, 0, size",
            "1, -1, size",
            "1, 101, size",
            "2147483647, 100, page"
    })
    void rejectsInvalidPagingAndOverflow(int page, int size, String field) {
        assertThatThrownBy(() -> agentService.search(criteria(), page, size))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo(field);
        verifyNoInteractions(agentRepository);
    }

    @Test
    void keepsRequestedPageAndTotalWhenPageIsBeyondLastResult() {
        AgentSearchCriteria criteria = criteria();
        PageRequest requestedPage = PageRequest.of(2, 20, Sort.by("agentCode"));
        when(agentRepository.search(isNull(), isNull(), eq(criteria.asOf()), eq(requestedPage)))
                .thenReturn(new PageImpl<>(List.of(), requestedPage, 21));

        PageResponse<AgentResponse> result = agentService.search(criteria, 3, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isEqualTo(3);
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(2);
    }

    private AgentSearchCriteria criteria() {
        return new AgentSearchCriteria(null, null, LocalDate.of(2026, 8, 11));
    }

    private AgentRow row(String rankCode, String status, boolean activeYn) {
        return new AgentRow(
                101L,
                "FC-SEOUL-001",
                "김설계",
                rankCode,
                11L,
                "FGC-BR-SEOUL",
                "서울지사",
                LocalDate.of(2026, 5, 1),
                null,
                status,
                LocalDate.of(2026, 5, 1),
                false,
                LocalDate.of(2026, 5, 1),
                true,
                LocalDate.of(2027, 4, 30),
                activeYn
        );
    }
}
