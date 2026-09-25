package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.CommissionItemResponse;
import com.susukkang.fgc.base.repository.CommissionItemRepository;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CommissionItemServiceTest {

    @Mock
    private CommissionItemRepository commissionItemRepository;

    @InjectMocks
    private CommissionItemService commissionItemService;

    @Test
    @DisplayName("기준일을 Repository에 전달하고 조회한 응답 필드와 순서를 유지한다")
    void findsCommissionItemsEffectiveOnTheGivenDate() {
        LocalDate asOf = LocalDate.of(2026, 8, 4);
        CommissionItemResponse payment = new CommissionItemResponse(
                11L, "BASE_COMMISSION", "FC 기본수수료", "PAYMENT", "SALES",
                LocalDate.of(2026, 1, 1), null
        );
        CommissionItemResponse deduction = new CommissionItemResponse(
                12L, "CLAWBACK", "환수", "DEDUCTION", "CLAWBACK",
                LocalDate.of(2026, 1, 1), asOf
        );
        given(commissionItemRepository.findEffectiveItems(asOf)).willReturn(List.of(payment, deduction));

        List<CommissionItemResponse> result = commissionItemService.findEffectiveItems(asOf);

        assertThat(result).containsExactly(payment, deduction);
        verify(commissionItemRepository).findEffectiveItems(asOf);
    }

    @Test
    @DisplayName("유효한 수수료 항목이 없으면 빈 목록을 반환한다")
    void returnsAnEmptyListWhenNoEffectiveItemExists() {
        LocalDate asOf = LocalDate.of(2025, 12, 31);
        given(commissionItemRepository.findEffectiveItems(asOf)).willReturn(List.of());

        assertThat(commissionItemService.findEffectiveItems(asOf)).isEmpty();
        verify(commissionItemRepository).findEffectiveItems(asOf);
    }

    @Test
    @DisplayName("기준일이 없으면 Repository를 호출하지 않고 기존 오류 메시지를 반환한다")
    void rejectsMissingAsOf() {
        assertThatNullPointerException()
                .isThrownBy(() -> commissionItemService.findEffectiveItems(null))
                .withMessage("기준일자(asOf)는 필수입니다.");
        verifyNoInteractions(commissionItemRepository);
    }
}
