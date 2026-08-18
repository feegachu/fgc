package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.contract.dto.InsuranceContract;
import com.susukkang.fgc.schedule.dto.ScheduleGenerationResult;
import com.susukkang.fgc.schedule.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설명 : ScheduleRegenerationBatchItemService
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-14
 */
@Service
@RequiredArgsConstructor
public class ScheduleRegenerationBatchItemService {
    private final ScheduleService scheduleService;

    /**
     * @return 생성·재생성된 스케줄 헤더 ID 목록 — 호출자(ValidationRunScheduleService)가
     *         이 목록을 validation_run_id에 연결한다(FGC-FUN-043 결과 집계).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ScheduleGenerationResult process(Long contractId) {
        InsuranceContract contract = InsuranceContract.builder()
                .contractId(contractId)
                .build();

        return scheduleService.generateSchedules(contract);
    }
}