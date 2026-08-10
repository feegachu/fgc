package com.susukkang.fgc.validation.batch.contract;

/** Step 3: 대상 계약의 현행 예상 스케줄을 재생성한다. 계약 단위 오류는 DATA_QUALITY 예외와 skip으로 반환할 수 있다. */
public interface ScheduleRegenerationPort {
    StepProcessingResult regenerateSchedules(ValidationStepContext context);
}
