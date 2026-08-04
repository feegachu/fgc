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
    // 스케줄 목록 조회
    /*
      request : paymentStage
      response : headers[] + lines[]
     */
//    @GetMapping
//    public ApiResponse<PageResponse<ScheduleResponse>> getSchedules(
//            ScheduleSearchCondition condition
//    ) {
//        return ApiResponse.ok(scheduleService.getSchedules(condition));
//    }

//    // 스케줄 상세 조회
//    @GetMapping("/{scheduleId}")
//    public ApiResponse<ScheduleDetailResponse> getSchedule(
//            @PathVariable Long scheduleId
//    ) {
//        return ApiResponse.ok(scheduleService.getSchedule(scheduleId));
//    }
//
//    // 새 버전으로 재생성
//    @PostMapping("/{scheduleId}/regenerate")
//    public ApiResponse<ScheduleRegenerateResponse> regenerate(
//            @PathVariable Long scheduleId,
//            @Valid @RequestBody ScheduleRegenerateRequest request
//    ) {
//        return ApiResponse.ok(scheduleService.regenerate(scheduleId, request));
//    }
}