package com.susukkang.fgc.schedule.controller;

import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.schedule.dto.ScheduleDetailResponse;
import com.susukkang.fgc.schedule.dto.ScheduleHeaderResponse;
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
     * @param condition,page,size 스케줄 계약 조건 및 페이지 정보
     * @return ApiResponse<PageResponse<ScheduleResponse>> 검색 조건에 해당하는 스케줄목록 및 페이지 정보
     * @author hjKang
     * @since 2026-08-07
     */
    @GetMapping
    public ApiResponse<PageResponse<ScheduleHeaderResponse>> getSchedulesByCondition(
            @ModelAttribute @Valid ScheduleSearchCondition condition,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(scheduleService.selectByCondition(condition,page,size));
    }

    /**
     * 설명 : 회차보기 버튼을 눌러 스케줄의 회차별 예상 금액을 확인한다
     *
     * @param  scheduleId 스케줄 헤더 Id
     * @return 스케줄 세부 내역
     * @author hjKang
     * @since 2026-08-07
     */
//    @GetMapping("/{scheduleId}")
//    public ApiResponse<ScheduleDetailResponse> getSchedule(@PathVariable Long scheduleId) {
//        return ApiResponse.success(scheduleService.getSchedule(scheduleId));
//    }

//    // 새 버전으로 재생성
//    @PostMapping("/{scheduleId}/regenerate")
//    public ApiResponse<ScheduleRegenerateResponse> regenerate(
//            @PathVariable Long scheduleId,
//            @Valid @RequestBody ScheduleRegenerateRequest request
//    ) {
//        return ApiResponse.success(scheduleService.regenerate(scheduleId, request));
//    }
}