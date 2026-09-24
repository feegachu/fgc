package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.code.InsurerType;
import com.susukkang.fgc.base.dto.InsurerResponse;
import com.susukkang.fgc.base.entity.Insurer;
import com.susukkang.fgc.base.repository.InsurerRepository;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.web.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsurerServiceImplTest {

    @Mock
    private InsurerRepository insurerRepository;

    private InsurerServiceImpl insurerService;

    @BeforeEach
    void setUp() {
        insurerService = new InsurerServiceImpl(insurerRepository);
    }

    @Test
    void trimsKeywordMapsLabelsAndBuildsPageResponse() {
        Insurer life = insurer(1L, "FGL01", "미래가상생명", InsurerType.LIFE, false);
        Insurer nonLife = insurer(2L, "FGL02", "미래가상손해보험", InsurerType.NON_LIFE, true);
        PageRequest pageable = PageRequest.of(1, 20, Sort.by("insurerCode").ascending());
        when(insurerRepository.search("fgl", pageable))
                .thenReturn(new PageImpl<>(List.of(life, nonLife), pageable, 22));

        PageResponse<InsurerResponse> result = insurerService.search("  fgl  ", 2, 20);

        verify(insurerRepository).search("fgl", pageable);
        verifyNoMoreInteractions(insurerRepository);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(22);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.sort()).isEqualTo("insurerCode,asc");
        assertThat(result.content()).containsExactly(
                new InsurerResponse(1L, "FGL01", "미래가상생명", "LIFE", "생명보험", false),
                new InsurerResponse(2L, "FGL02", "미래가상손해보험", "NON_LIFE", "손해보험", true)
        );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void convertsMissingOrBlankKeywordToNull(String keyword) {
        PageRequest pageable = PageRequest.of(0, 20, Sort.by("insurerCode").ascending());
        when(insurerRepository.search(null, pageable)).thenReturn(Page.empty(pageable));

        PageResponse<InsurerResponse> result = insurerService.search(keyword, 1, 20);

        verify(insurerRepository).search(null, pageable);
        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
        assertThat(result.sort()).isEqualTo("insurerCode,asc");
    }

    @Test
    void returnsEmptyPageWhenKeywordHasNoMatches() {
        PageRequest pageable = PageRequest.of(0, 20, Sort.by("insurerCode").ascending());
        when(insurerRepository.search("없는보험사", pageable)).thenReturn(Page.empty(pageable));

        PageResponse<InsurerResponse> result = insurerService.search("없는보험사", 1, 20);

        verify(insurerRepository).search("없는보험사", pageable);
        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    void keepsFilteredTotalWhenRequestedPageIsBeyondLastPage() {
        PageRequest pageable = PageRequest.of(3, 20, Sort.by("insurerCode").ascending());
        when(insurerRepository.search("생명", pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 21));

        PageResponse<InsurerResponse> result = insurerService.search("생명", 4, 20);

        verify(insurerRepository).search("생명", pageable);
        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isEqualTo(4);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void acceptsPageSizeBoundaries(int size) {
        PageRequest pageable = PageRequest.of(0, size, Sort.by("insurerCode").ascending());
        when(insurerRepository.search(null, pageable)).thenReturn(Page.empty(pageable));

        PageResponse<InsurerResponse> result = insurerService.search(null, 1, size);

        verify(insurerRepository).search(null, pageable);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(size);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsPageBelowOne(int page) {
        assertThatThrownBy(() -> insurerService.search(null, page, 20))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("page");
        verifyNoInteractions(insurerRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void rejectsSizeOutsideOneToOneHundred(int size) {
        assertThatThrownBy(() -> insurerService.search(null, 1, size))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("size");
        verifyNoInteractions(insurerRepository);
    }

    @Test
    void rejectsPageWhenOffsetExceedsIntegerRange() {
        assertThatThrownBy(() -> insurerService.search(null, Integer.MAX_VALUE, 100))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("page");
        verifyNoInteractions(insurerRepository);
    }

    private Insurer insurer(Long id, String code, String name, InsurerType type, boolean active) {
        Insurer insurer = new Insurer();
        ReflectionTestUtils.setField(insurer, "insurerId", id);
        ReflectionTestUtils.setField(insurer, "insurerCode", code);
        ReflectionTestUtils.setField(insurer, "insurerName", name);
        ReflectionTestUtils.setField(insurer, "insurerType", type);
        ReflectionTestUtils.setField(insurer, "activeYn", active);
        return insurer;
    }
}
