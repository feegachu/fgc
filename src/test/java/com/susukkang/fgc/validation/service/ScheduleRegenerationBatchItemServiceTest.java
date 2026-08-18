package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.contract.dto.InsuranceContract;
import com.susukkang.fgc.schedule.dto.ScheduleGenerationResult;
import com.susukkang.fgc.schedule.mapper.ScheduleMapper;
import com.susukkang.fgc.schedule.service.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * FGC-FUN-043 — 스케줄 생성과 validation_run_id 연결이 같은 REQUIRES_NEW 트랜잭션
 * 안에서 함께 처리되는지 확인한다(코드리뷰 반영: 예전에는 연결 UPDATE가 호출자의 바깥
 * 트랜잭션에 있어, 바깥 트랜잭션이 나중에 롤백되면 이미 커밋된 헤더가
 * validation_run_id=NULL로 남는 문제가 있었다).
 */
@ExtendWith(MockitoExtension.class)
class ScheduleRegenerationBatchItemServiceTest {

    @Mock
    private ScheduleService scheduleService;
    @Mock
    private ScheduleMapper scheduleMapper;

    private ScheduleRegenerationBatchItemService service;

    @BeforeEach
    void setUp() {
        service = new ScheduleRegenerationBatchItemService(scheduleService, scheduleMapper);
    }

    @Test
    void linksNewlyCreatedHeadersToTheValidationRun() {
        given(scheduleService.generateSchedules(any(InsuranceContract.class)))
                .willReturn(new ScheduleGenerationResult(List.of(200L, 201L), 24));

        service.process(10L, 118L);

        verify(scheduleMapper).linkHeadersToValidationRun(List.of(200L, 201L), 118L);
    }

    @Test
    void doesNotCallLinkWhenNoHeadersWereCreated() {
        // 활성 스케줄이 이미 있어 generateSchedules()가 새 헤더를 안 만드는 경우 —
        // linkHeadersToValidationRun 호출 자체가 없어야 한다(빈 IN절 UPDATE 방지).
        given(scheduleService.generateSchedules(any(InsuranceContract.class)))
                .willReturn(new ScheduleGenerationResult(List.of(), 0));

        service.process(10L, 118L);

        verify(scheduleMapper, never()).linkHeadersToValidationRun(any(), any());
    }
}
