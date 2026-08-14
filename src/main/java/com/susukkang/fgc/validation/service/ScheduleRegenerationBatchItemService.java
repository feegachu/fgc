package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.contract.dto.InsuranceContract;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long contractId) {
        InsuranceContract contract = InsuranceContract.builder()
                .contractId(contractId)
                .build();

        scheduleService.generateSchedules(contract);
    }
}