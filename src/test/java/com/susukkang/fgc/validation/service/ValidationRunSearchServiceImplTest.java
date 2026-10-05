package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunSearchCriteria;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

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
    private ValidationRunRepository validationRunRepository;

    private ValidationRunSearchServiceImpl service;

    private ValidationRunListRow sampleRow() {
        return new ValidationRunListRow(
                100L, LocalDate.of(2026, 8, 1), 1, "MONTHLY", "RUNNING", 5,
                "settle01", null, null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        service = new ValidationRunSearchServiceImpl(validationRunRepository);
    }

    @Test
    void searchBuildsPageResponseFromRepositoryResults() {
        ValidationRunSearchCriteria criteria = new ValidationRunSearchCriteria(LocalDate.of(2026, 8, 1), "RUNNING");
        PageRequest pageable = PageRequest.of(0, 20);
        Page<ValidationRunListRow> page = new PageImpl<>(List.of(sampleRow()), pageable, 1);
        when(validationRunRepository.search(criteria.month(), ValidationRunStatus.RUNNING, pageable))
                .thenReturn(page);

        PageResponse<ValidationRunListRow> result = service.search(criteria, 1, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).getValidationRunId()).isEqualTo(100L);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
    }

    @Test
    // month/status 둘 다 없으면(전체 조회) null 그대로 Repository에 넘겨야 한다
    void searchPassesNullCriteriaThroughWhenNoFilterGiven() {
        ValidationRunSearchCriteria criteria = new ValidationRunSearchCriteria(null, null);
        PageRequest pageable = PageRequest.of(0, 20);
        when(validationRunRepository.search(null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        PageResponse<ValidationRunListRow> result = service.search(criteria, 1, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    // 2페이지·size 10 이면 PageRequest.of(1, 10)이어야 한다(0-base)
    void searchComputesPageableFromPageAndSize() {
        ValidationRunSearchCriteria criteria = new ValidationRunSearchCriteria(null, null);
        PageRequest pageable = PageRequest.of(1, 10);
        when(validationRunRepository.search(null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(sampleRow()), pageable, 11));

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
