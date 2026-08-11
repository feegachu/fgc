package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunSearchCriteria;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * ValidationRunSearchServiceImpl 단위테스트
 */
@ExtendWith(MockitoExtension.class)
class ValidationRunSearchServiceImplTest {

    @Mock
    private ValidationRunMapper validationRunMapper;

    private ValidationRunSearchServiceImpl service;

    private ValidationRunListRow sampleRow() {
        ValidationRunListRow row = new ValidationRunListRow();
        row.setValidationRunId(100L);
        row.setValidationMonth(LocalDate.of(2026, 8, 1));
        row.setRunNo(1);
        row.setRunType("MONTHLY");
        row.setStatus("RUNNING");
        row.setCurrentStep(5);
        row.setTriggeredBy("settle01");
        return row;
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new ValidationRunSearchServiceImpl(validationRunMapper);
    }

    @Test
    void searchBuildsPageResponseFromMapperResults() {
        ValidationRunSearchCriteria criteria = new ValidationRunSearchCriteria(LocalDate.of(2026, 8, 1), "RUNNING");
        when(validationRunMapper.search(criteria.month(), criteria.status(), 0, 20))
                .thenReturn(List.of(sampleRow()));
        when(validationRunMapper.count(criteria.month(), criteria.status())).thenReturn(1L);

        PageResponse<ValidationRunListRow> result = service.search(criteria, 1, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).getValidationRunId()).isEqualTo(100L);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
    }

    @Test
    // month/status 둘 다 없으면(전체 조회) null 그대로 매퍼에 넘겨야 한다
    void searchPassesNullCriteriaThroughWhenNoFilterGiven() {
        ValidationRunSearchCriteria criteria = new ValidationRunSearchCriteria(null, null);
        when(validationRunMapper.search(null, null, 0, 20)).thenReturn(List.of());
        when(validationRunMapper.count(null, null)).thenReturn(0L);

        PageResponse<ValidationRunListRow> result = service.search(criteria, 1, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    // 2페이지·size 10 이면 offset은 (2-1)*10 = 10이어야 한다
    void searchComputesOffsetFromPageAndSize() {
        ValidationRunSearchCriteria criteria = new ValidationRunSearchCriteria(null, null);
        when(validationRunMapper.search(null, null, 10, 10)).thenReturn(List.of(sampleRow()));
        when(validationRunMapper.count(null, null)).thenReturn(11L);

        PageResponse<ValidationRunListRow> result = service.search(criteria, 2, 10);

        assertThat(result.content()).hasSize(1);
        assertThat(result.page()).isEqualTo(2);
    }

    @Test
    void searchRejectsPageBelowOne() {
        ValidationRunSearchCriteria criteria = new ValidationRunSearchCriteria(null, null);

        assertThatThrownBy(() -> service.search(criteria, 0, 20))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.COMMON_002);
    }

    @Test
    void searchRejectsSizeBelowOne() {
        ValidationRunSearchCriteria criteria = new ValidationRunSearchCriteria(null, null);

        assertThatThrownBy(() -> service.search(criteria, 1, 0))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.COMMON_002);
    }

    @Test
    void searchRejectsSizeAboveOneHundred() {
        ValidationRunSearchCriteria criteria = new ValidationRunSearchCriteria(null, null);

        assertThatThrownBy(() -> service.search(criteria, 1, 101))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.COMMON_002);
    }
}
