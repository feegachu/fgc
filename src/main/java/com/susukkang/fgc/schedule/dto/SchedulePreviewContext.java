package com.susukkang.fgc.schedule.dto;
import com.susukkang.fgc.policy.dto.FeeRegimeSchedulePolicy;
/** 서버가 선택한 예정 생성 입력. SELECTED는 지급 확정을 의미하지 않는다. */
public record SchedulePreviewContext(FeeRegimeSchedulePolicy policy, String expectedSourceVersion) { }
