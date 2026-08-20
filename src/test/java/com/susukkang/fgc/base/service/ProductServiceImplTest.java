package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.ProductResponse;
import com.susukkang.fgc.base.dto.ProductRow;
import com.susukkang.fgc.base.dto.ProductSearchCriteria;
import com.susukkang.fgc.base.mapper.ProductMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.web.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    private static final String SORT =
            "insurerProductCode,asc;offeringVersion,asc;channelCode,asc;productOfferingId,asc";

    @Mock
    private ProductMapper productMapper;

    private ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        productService = new ProductServiceImpl(productMapper);
    }

    @Test
    void mapsRowsAndBuildsOneBasedPageResponse() {
        ProductSearchCriteria criteria = criteria(2L);
        ProductRow row = new ProductRow(
                21L, "P-B-001", "STD-LIFE-B", "가상 저해지 건강보험 B",
                "HEALTH_PROTECTION", "2026-CURRENT-B",
                LocalDate.of(2026, 7, 1), null, "BD-2026-07",
                LocalDate.of(2026, 7, 1), "FACE_TO_FACE", false, "CURRENT", true, 240
        );
        when(productMapper.selectProducts(criteria, 20, 20)).thenReturn(List.of(row));
        when(productMapper.countProducts(criteria)).thenReturn(21L);

        PageResponse<ProductResponse> result = productService.search(criteria, 2, 20);

        verify(productMapper).selectProducts(criteria, 20, 20);
        verify(productMapper).countProducts(criteria);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.sort()).isEqualTo(SORT);
        assertThat(result.content()).singleElement().satisfies(response -> {
            assertThat(response.productOfferingId()).isEqualTo(21L);
            assertThat(response.channelCode()).isEqualTo("FACE_TO_FACE");
            assertThat(response.standardDeduction80Yn()).isTrue();
            assertThat(response.paymentTermMonths()).isEqualTo(240);
        });
    }

    @Test
    void returnsEmptyPageForUnknownPositiveInsurer() {
        ProductSearchCriteria criteria = criteria(999_999L);
        when(productMapper.selectProducts(criteria, 0, 20)).thenReturn(List.of());
        when(productMapper.countProducts(criteria)).thenReturn(0L);

        PageResponse<ProductResponse> result = productService.search(criteria, 1, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    void rejectsNonPositiveInsurerId() {
        assertThatThrownBy(() -> productService.search(criteria(0L), 1, 20))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("insurerId");
    }

    @Test
    void rejectsPageBelowOne() {
        assertThatThrownBy(() -> productService.search(criteria(2L), 0, 20))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("page");
    }

    @Test
    void rejectsSizeOutsideOneToOneHundred() {
        assertThatThrownBy(() -> productService.search(criteria(2L), 1, 0))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("size");
        assertThatThrownBy(() -> productService.search(criteria(2L), 1, 101))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("size");
    }

    @Test
    void rejectsPageWhenOffsetExceedsIntegerRange() {
        assertThatThrownBy(() -> productService.search(criteria(2L), Integer.MAX_VALUE, 100))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field")
                .isEqualTo("page");
    }

    private ProductSearchCriteria criteria(long insurerId) {
        return new ProductSearchCriteria(insurerId, LocalDate.of(2026, 8, 11));
    }
}
