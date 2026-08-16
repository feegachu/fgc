package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.InsurerResponse;
import com.susukkang.fgc.base.dto.InsurerRow;
import com.susukkang.fgc.base.mapper.InsurerMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.web.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsurerServiceImplTest {

    @Mock
    private InsurerMapper insurerMapper;

    private InsurerServiceImpl insurerService;

    @BeforeEach
    void setUp() {
        insurerService = new InsurerServiceImpl(insurerMapper);
    }

    @Test
    void trimsKeywordMapsLabelsAndBuildsPageResponse() {
        InsurerRow row = new InsurerRow(1L, "FGL01", "미래가상생명", "LIFE", false);

        when(insurerMapper.selectInsurers(anyString(), eq(20), eq(20)))
                .thenReturn(List.of(row));
        when(insurerMapper.countInsurers(anyString())).thenReturn(21L);

        PageResponse<InsurerResponse> result = insurerService.search("  fgl  ", 2, 20);

        verify(insurerMapper).selectInsurers(eq("fgl"), eq(20), eq(20));
        verify(insurerMapper).countInsurers(eq("fgl"));
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.sort()).isEqualTo("insurerCode,asc");
        assertThat(result.content()).singleElement().satisfies(response -> {
            assertThat(response.insurerCode()).isEqualTo("FGL01");
            assertThat(response.insurerType()).isEqualTo("LIFE");
            assertThat(response.insurerTypeLabel()).isEqualTo("생명보험");
            assertThat(response.activeYn()).isFalse();
        });
    }

    @Test
    void convertsBlankKeywordToNull() {
        when(insurerMapper.selectInsurers(isNull(), eq(0), eq(20)))
                .thenReturn(List.of());
        when(insurerMapper.countInsurers(isNull())).thenReturn(0L);

        insurerService.search("   ", 1, 20);

        verify(insurerMapper).selectInsurers(isNull(), eq(0), eq(20));
        verify(insurerMapper).countInsurers(isNull());
    }

    @Test
    void rejectsPageBelowOne() {
        assertThatThrownBy(() -> insurerService.search(null, 0, 20))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("page");
    }

    @Test
    void rejectsSizeOutsideOneToOneHundred() {
        assertThatThrownBy(() -> insurerService.search(null, 1, 0))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("size");
        assertThatThrownBy(() -> insurerService.search(null, 1, 101))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("size");
    }

    @Test
    void rejectsPageWhenOffsetExceedsIntegerRange() {
        assertThatThrownBy(() -> insurerService.search(null, Integer.MAX_VALUE, 100))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("page");
    }
}
