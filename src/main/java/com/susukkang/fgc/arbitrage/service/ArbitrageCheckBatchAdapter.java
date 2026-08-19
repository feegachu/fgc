package com.susukkang.fgc.arbitrage.service;

import com.susukkang.fgc.arbitrage.mapper.ArbitrageMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.validation.batch.contract.ArbitrageCheckBatchPort;
import com.susukkang.fgc.validation.batch.contract.ContractSkip;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 설명 : 월 검증 실행에서 선별된 계약의 차익거래 검증을 수행한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Service
@RequiredArgsConstructor
public class ArbitrageCheckBatchAdapter implements ArbitrageCheckBatchPort {
    private final ArbitrageMapper arbitrageMapper;
    private final ArbitrageCheckBatchItemService arbitrageCheckBatchItemService;

    @Override
    public StepProcessingResult check(ValidationStepContext context) {
        List<Long> contractIds =
                arbitrageMapper.selectSelectedContractIds(context.validationRunId());
        LocalDate asOfDate = context.job().validationMonth().plusMonths(1).minusDays(1);
        long processedCount = 0;
        List<ContractSkip> skips = new ArrayList<>();

        for (Long contractId : contractIds) {
            try {
                // REQUIRES_NEW — 이 계약만 실패해도 청크(다른 계약들)의 트랜잭션을
                // rollback-only로 오염시키지 않는다(아래 catch의 skip이 실제로 동작하려면 필수,
                // #266 changedContractStep과 동일한 근본 원인).
                arbitrageCheckBatchItemService.process(
                        context.validationRunId(), contractId, asOfDate);
                processedCount++;
            } catch (FgcBusinessException exception) {
                skips.add(new ContractSkip(
                        contractId,
                        "ARBITRAGE_CHECK_FAILED",
                        exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()
                ));
            }
        }
        return new StepProcessingResult(processedCount, skips.size(), 0, skips);
    }
}
