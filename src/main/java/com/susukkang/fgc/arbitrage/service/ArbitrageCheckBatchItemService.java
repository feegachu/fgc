package com.susukkang.fgc.arbitrage.service;

import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckInsertDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * arbitrageCheckStep 전용 진입점. checkInExistingRun()을 REQUIRES_NEW로 별도 트랜잭션에 담아,
 * 이 계약이 FgcBusinessException으로 실패해도 청크(다른 계약들)의 바깥 트랜잭션을
 * rollback-only로 오염시키지 않는다 — CapCheckBatchItemService·ScheduleRegenerationBatchItemService와
 * 같은 패턴(#266에서 changedContractStep에 적용한 것과 동일한 근본 원인).
 */
@Service
@RequiredArgsConstructor
public class ArbitrageCheckBatchItemService {
    private final ArbitrageService arbitrageService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ArbitrageCheckInsertDTO process(Long validationRunId, Long contractId, LocalDate asOfDate) {
        return arbitrageService.checkInExistingRun(validationRunId, contractId, asOfDate);
    }
}
