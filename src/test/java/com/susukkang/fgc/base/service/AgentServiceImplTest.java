package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.AgentResponse;
import com.susukkang.fgc.base.dto.AgentRow;
import com.susukkang.fgc.base.dto.AgentSearchCriteria;
import com.susukkang.fgc.base.mapper.AgentMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceImplTest {

    @Mock
    private AgentMapper agentMapper;

    private AgentServiceImpl agentService;

    @BeforeEach
    void setUp() {
        agentService = new AgentServiceImpl(agentMapper);
    }

    @Test
    void normalizesCriteriaMapsLabelsAndBuildsPageResponse() {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        AgentSearchCriteria input = new AgentSearchCriteria(11L, "  kim  ", asOf);
        AgentRow row = row("FC", "INACTIVE", false);
        ArgumentCaptor<AgentSearchCriteria> criteriaCaptor =
                ArgumentCaptor.forClass(AgentSearchCriteria.class);

        when(agentMapper.selectAgents(any(AgentSearchCriteria.class), eq(20), eq(20)))
                .thenReturn(List.of(row));
        when(agentMapper.countAgents(any(AgentSearchCriteria.class))).thenReturn(21L);

        PageResponse<AgentResponse> result = agentService.search(input, 2, 20);

        verify(agentMapper).selectAgents(criteriaCaptor.capture(), eq(20), eq(20));
        assertThat(criteriaCaptor.getValue()).isEqualTo(new AgentSearchCriteria(11L, "kim", asOf));
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.sort()).isEqualTo("agentCode,asc");
        assertThat(result.content()).singleElement().satisfies(response -> {
            assertThat(response.rankLabel()).isEqualTo("설계사");
            assertThat(response.agentStatusLabel()).isEqualTo("비활동");
            assertThat(response.organizationId()).isEqualTo(11L);
            assertThat(response.activeYn()).isFalse();
        });
    }

    @Test
    void convertsBlankKeywordToNull() {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        ArgumentCaptor<AgentSearchCriteria> criteriaCaptor =
                ArgumentCaptor.forClass(AgentSearchCriteria.class);
        when(agentMapper.selectAgents(any(AgentSearchCriteria.class), eq(0), eq(20)))
                .thenReturn(List.of());
        when(agentMapper.countAgents(any(AgentSearchCriteria.class))).thenReturn(0L);

        agentService.search(new AgentSearchCriteria(null, "   ", asOf), 1, 20);

        verify(agentMapper).selectAgents(criteriaCaptor.capture(), eq(0), eq(20));
        assertThat(criteriaCaptor.getValue().keyword()).isNull();
    }

    @Test
    void rejectsInvalidOrganizationId() {
        assertThatThrownBy(() -> agentService.search(
                new AgentSearchCriteria(0L, null, LocalDate.of(2026, 8, 11)), 1, 20))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("organizationId");
    }

    @Test
    void rejectsInvalidPagingAndOverflow() {
        assertThatThrownBy(() -> agentService.search(criteria(), 0, 20))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("page");
        assertThatThrownBy(() -> agentService.search(criteria(), 1, 101))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("size");
        assertThatThrownBy(() -> agentService.search(criteria(), Integer.MAX_VALUE, 100))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("page");
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
