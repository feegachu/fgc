package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.RefundRateQuery;
import com.susukkang.fgc.cap.dto.RefundRateResolution;
import com.susukkang.fgc.cap.dto.RefundRateTableView;
import com.susukkang.fgc.cap.mapper.RefundRateMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductRefundRateResolverImplTest {

    @Mock
    private RefundRateMapper refundRateMapper;

    private ProductRefundRateResolverImpl resolverWithMapper() {
        return new ProductRefundRateResolverImpl(refundRateMapper);
    }

    @Test
    void 표와_12차월_값이_있으면_표버전과_환급률을_돌려준다() {
        RefundRateTableView table = new RefundRateTableView();
        table.setRefundRateTableId(777L);
        table.setPolicyVersionId(55L);
        table.setVersionNo(3);
        table.setStandardDeduction80Yn(true);
        table.setEffectiveFrom(LocalDate.of(2026, 1, 1));

        RefundRateQuery query = new RefundRateQuery(10L, 100L, 240, "FACE_TO_FACE", LocalDate.of(2026, 1, 15));

        when(refundRateMapper.findApplicableTable(10L, 100L, 240, "FACE_TO_FACE", query.asOfDate()))
                .thenReturn(table);
        when(refundRateMapper.findRateAtMonth(eq(777L), eq(12))).thenReturn(new BigDecimal("24.000000"));

        Optional<RefundRateResolution> result = resolverWithMapper().resolve(query);

        assertThat(result).isPresent();
        assertThat(result.get().refundRateTableId()).isEqualTo(777L);
        assertThat(result.get().policyVersionId()).isEqualTo(55L);
        assertThat(result.get().versionNo()).isEqualTo(3);
        assertThat(result.get().month12RatePct()).isEqualByComparingTo("24.000000");
    }

    @Test
    void 표가_없으면_빈값을_돌려준다() {
        RefundRateQuery query = new RefundRateQuery(10L, 999L, 240, "FACE_TO_FACE", LocalDate.of(2026, 1, 15));
        when(refundRateMapper.findApplicableTable(any(), any(), any(), any(), any())).thenReturn(null);

        assertThat(resolverWithMapper().resolve(query)).isEmpty();
    }

    @Test
    void 표는_있지만_12차월_값이_없으면_빈값을_돌려준다() {
        RefundRateTableView table = new RefundRateTableView();
        table.setRefundRateTableId(777L);
        table.setPolicyVersionId(55L);
        table.setVersionNo(1);

        RefundRateQuery query = new RefundRateQuery(10L, 100L, 120, "FACE_TO_FACE", LocalDate.of(2026, 1, 15));
        when(refundRateMapper.findApplicableTable(any(), any(), any(), any(), any())).thenReturn(table);
        when(refundRateMapper.findRateAtMonth(eq(777L), anyInt())).thenReturn(null);

        assertThat(resolverWithMapper().resolve(query)).isEmpty();
    }
}
