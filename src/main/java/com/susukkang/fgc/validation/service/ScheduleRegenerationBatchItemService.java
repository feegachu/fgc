package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.contract.dto.InsuranceContract;
import com.susukkang.fgc.schedule.dto.ScheduleGenerationResult;
import com.susukkang.fgc.schedule.mapper.ScheduleMapper;
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
    private final ScheduleMapper scheduleMapper;

    /**
     * 스케줄 생성·재생성과 validation_run_id 연결(FGC-FUN-043 결과 집계)을 같은
     * REQUIRES_NEW 트랜잭션 안에서 처리한다(코드리뷰 반영) — 원래는 생성만 여기서 커밋하고
     * 연결 UPDATE는 호출자(ValidationRunScheduleService)의 바깥 트랜잭션에서 했는데,
     * 그 사이 다른 계약 처리 중 예외가 나서 바깥 트랜잭션이 롤백되면 연결 UPDATE만
     * 롤백되고 이미 커밋된 헤더는 validation_run_id=NULL로 남는 문제가 있었다. 생성과
     * 연결을 한 트랜잭션으로 묶으면 실패 시 헤더·라인·연결이 전부 함께 롤백된다.
     *
     * @return 생성·재생성된 스케줄 헤더 ID 목록(참고용 — 연결은 이미 이 메서드가 끝냈다)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ScheduleGenerationResult process(Long contractId, Long validationRunId) {
        InsuranceContract contract = InsuranceContract.builder()
                .contractId(contractId)
                .build();

        ScheduleGenerationResult result = scheduleService.generateSchedules(contract);
        if (!result.scheduleHeaderIds().isEmpty()) {
            scheduleMapper.linkHeadersToValidationRun(result.scheduleHeaderIds(), validationRunId);
        }
        return result;
    }
}