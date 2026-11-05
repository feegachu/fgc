package com.susukkang.fgc.dashboard.controller;

import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.dashboard.dto.HomeSummaryResponse;
import com.susukkang.fgc.dashboard.service.HomeSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * FGC-UI-DASH-W01(FUN-080) 관리자 홈 현황 API
 */
@Tag(name = "업무 대시보드", description = "FGC-UI-DASH-W01 요약 집계 조회 API")
@RestController
@RequestMapping("/api/v1/home")
@RequiredArgsConstructor
public class HomeSummaryController {

    private final HomeSummaryService homeSummaryService;

    @Operation(
            summary = "관리자 홈 GA·설계사 현황 (IF-API-68)",
            description = "기준일의 등록·활동·해촉 설계사 수, 지점별 설계사·유효 계약 수, 확인할 설계사 상위 10명을 돌려준다. "
                    + "asOf 생략 시 오늘(Asia/Seoul)을 쓴다. 전체 설계사 검색은 IF-API-07 을 쓴다."
    )
    @GetMapping("/summary")
    @PreAuthorize(Roles.ANY_ROLE)
    public ApiResponse<HomeSummaryResponse> homeSummary(
            @Parameter(description = "현황 기준일(yyyy-MM-dd). 생략하면 오늘", example = "2026-11-04")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        return ApiResponse.success(homeSummaryService.summarize(asOf));
    }
}
