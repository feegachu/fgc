package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.CommissionItemResponse;
import com.susukkang.fgc.base.mapper.CommissionItemMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CommissionItemServiceTest {

    @Mock
    private CommissionItemMapper commissionItemMapper;

    @InjectMocks
    private CommissionItemService commissionItemService;

    @Test
    void findsCommissionItemsEffectiveOnTheGivenDate() {
        LocalDate asOf = LocalDate.of(2026, 8, 4);
        CommissionItemResponse item = new CommissionItemResponse(
                "BASE_COMMISSION", "FC 기본수수료", "PAYMENT", "SALES"
        );
        given(commissionItemMapper.findEffectiveItems(asOf)).willReturn(List.of(item));

        List<CommissionItemResponse> result = commissionItemService.findEffectiveItems(asOf);

        assertThat(result).containsExactly(item);
        verify(commissionItemMapper).findEffectiveItems(asOf);
    }

    @Test
    void returnsAnEmptyListWhenNoEffectiveItemExists() {
        LocalDate asOf = LocalDate.of(2025, 12, 31);
        given(commissionItemMapper.findEffectiveItems(asOf)).willReturn(List.of());

        assertThat(commissionItemService.findEffectiveItems(asOf)).isEmpty();
    }

    @Test
    void rejectsMissingAsOf() {
        assertThatNullPointerException()
                .isThrownBy(() -> commissionItemService.findEffectiveItems(null))
                .withMessage("기준일자(asOf)는 필수입니다.");
    }
}
