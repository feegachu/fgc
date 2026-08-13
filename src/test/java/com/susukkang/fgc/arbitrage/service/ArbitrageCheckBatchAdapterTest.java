package com.susukkang.fgc.arbitrage.service;

import com.susukkang.fgc.arbitrage.mapper.ArbitrageMapper;
import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckInsertDTO;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 설명 : 월 검증 차익거래 어댑터의 계약별 처리 및 skip 집계를 검증한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@ExtendWith(MockitoExtension.class)
class ArbitrageCheckBatchAdapterTest {
    @Mock
    private ArbitrageMapper arbitrageMapper;
    @Mock
    private ArbitrageService arbitrageService;

    @Test
    void checksSelectedContractsAndReturnsRecoverableFailureAsSkip() {
        ArbitrageCheckBatchAdapter adapter =
                new ArbitrageCheckBatchAdapter(arbitrageMapper, arbitrageService);
        ValidationStepContext context = new ValidationStepContext(
                100L,
                new ValidationJobContext(
                        LocalDate.of(2026, 7, 1),
                        1,
                        ValidationRunType.MONTHLY,
                        1,
                        "request-1")
        );
        given(arbitrageMapper.selectSelectedContractIds(100L)).willReturn(List.of(10L, 20L));
        given(arbitrageService.checkInExistingRun(
                100L, 10L, LocalDate.of(2026, 7, 31)))
                .willReturn(new ArbitrageCheckInsertDTO());
        doThrow(new FgcBusinessException(FgcErrorCode.COMMON_004, java.util.Map.of()))
                .when(arbitrageService)
                .checkInExistingRun(100L, 20L, LocalDate.of(2026, 7, 31));

        StepProcessingResult result = adapter.check(context);

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.skips()).singleElement().satisfies(skip -> {
            assertThat(skip.contractId()).isEqualTo(20L);
            assertThat(skip.reasonCode()).isEqualTo("ARBITRAGE_CHECK_FAILED");
        });
        verify(arbitrageService).checkInExistingRun(100L, 10L, LocalDate.of(2026, 7, 31));
    }
}
