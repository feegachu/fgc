package com.susukkang.fgc.schedule.controller;

import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.schedule.dto.ScheduleResponse;
import com.susukkang.fgc.schedule.dto.ScheduleSearchCondition;
import com.susukkang.fgc.schedule.service.ScheduleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    ScheduleService scheduleService;
    /**
     * 설명 : 검색 조건에 따라 스케줄을 조회한다
     *
     * @param condition 스케줄 계약 조건
     * @return 검색 조건에 해당하는 스케줄 목록
     * @author hjKang
     * @since 2026-08-07
     */
    @GetMapping
    public ApiResponse<PageResponse<ScheduleResponse>> getSchedules(
            ScheduleSearchCondition condition
    ) {
        return ApiResponse.success(scheduleService.getSchedules(condition));
    }

    /**
     * 설명 : 회차보기 버튼을 눌러 스케줄의 회차별 예상 금액을 확인한다
     *
     * @param  scheduleId
     * @return 검색 조건에 해당하는 스케줄 회차 목록
     * @author hjKang
     * @since 2026-08-07
     */
    @GetMapping("/{scheduleId}")
    public ApiResponse<ScheduleDetailResponse> getSchedule(
            @PathVariable Long scheduleId
    ) {
        return ApiResponse.success(scheduleService.getSchedule(scheduleId));
    }

    // 새 버전으로 재생성
    @PostMapping("/{scheduleId}/regenerate")
    public ApiResponse<ScheduleRegenerateResponse> regenerate(
            @PathVariable Long scheduleId,
            @Valid @RequestBody ScheduleRegenerateRequest request
    ) {
        return ApiResponse.success(scheduleService.regenerate(scheduleId, request));
    }
}