package com.susukkang.fgc.schedule.controller;

import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.schedule.dto.*;
import com.susukkang.fgc.schedule.service.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;
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
     * @param  scheduleHeaderId 스케줄 헤더 Id
     * @return 스케줄 세부 내역
     * @author hjKang
     * @since 2026-08-07
     */
    @GetMapping("/{scheduleHeaderId}")
    public ApiResponse<ScheduleDetailResponse> getScheduleLineById(@PathVariable Long scheduleHeaderId) {
        return ApiResponse.success(scheduleService.selectScheduleDetailById(scheduleHeaderId));
    }

    /**
     * 설명 : 스케줄을 새 버전으로 재생성한다.
     *
     * @param  id 스케줄ID
     * @return
     * @author hjKang
     * @since 2026-08-11
     */
    @PreAuthorize("hasAnyRole('SETTLEMENT', 'SYSTEM_ADMIN')")
    @PostMapping("/{id}/regenerate")
    public ApiResponse<ScheduleRegenResponse> regenerate(
            @PathVariable Long id,
            @Valid @RequestBody ScheduleRegenRequest request) {
        return ApiResponse.success(scheduleService.regenerateSchedules(id, request.getReason()));
    }
}
