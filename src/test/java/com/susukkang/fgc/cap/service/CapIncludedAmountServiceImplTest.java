package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapIncludedAmountSummary;
import com.susukkang.fgc.cap.repository.CapIncludedAmountQueryRepository;
import com.susukkang.fgc.common.code.PaymentStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class CapIncludedAmountServiceImplTest {

    @Mock
    private CapIncludedAmountQueryRepository capIncludedAmountQueryRepository;

    @InjectMocks
    private CapIncludedAmountServiceImpl capIncludedAmountService;

    @Test
    void sumsConfirmedAndCurrentTransactionAmountsBeforeConfirmation() {
        given(capIncludedAmountQueryRepository.sumIncludedAmountByContractAndAgent(10L, 100L, PaymentStage.GA_TO_FC))
                .willReturn(List.of(summary(1L, "400000"), summary(2L, "300000")));

        BigDecimal result = capIncludedAmountService.calculatePreConfirmAmount(10L, 100L, PaymentStage.GA_TO_FC);

        assertThat(result).isEqualByComparingTo("700000");
    }

    @Test
    void recalculatesAllConfirmedAmountsForMonthlyValidation() {
        given(capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(10L, PaymentStage.GA_TO_FC))
                .willReturn(List.of(summary(1L, "500000"), summary(2L, "250000")));

        BigDecimal result = capIncludedAmountService.recalculateTotalAmount(10L, PaymentStage.GA_TO_FC);

        assertThat(result).isEqualByComparingTo("750000");
    }

    @Test
    void returnsZeroBeforeConfirmationWhenNoAmountsAreIncluded() {
        given(capIncludedAmountQueryRepository.sumIncludedAmountByContractAndAgent(10L, 100L, PaymentStage.GA_TO_FC))
                .willReturn(List.of());

        assertThat(capIncludedAmountService.calculatePreConfirmAmount(10L, 100L, PaymentStage.GA_TO_FC))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void returnsZeroForMonthlyValidationWhenNoAmountsAreIncluded() {
        given(capIncludedAmountQueryRepository.sumConfirmedIncludedAmountByContractAndAgent(10L, PaymentStage.GA_TO_FC))
                .willReturn(List.of());

        assertThat(capIncludedAmountService.recalculateTotalAmount(10L, PaymentStage.GA_TO_FC))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    private CapIncludedAmountSummary summary(Long agentId, String includedAmount) {
        return CapIncludedAmountSummary.builder().contractId(10L).agentId(agentId).paymentStage(PaymentStage.GA_TO_FC).includedAmount(new BigDecimal(includedAmount)).build();
    }
}
