package com.susukkang.fgc.dashboard.controller;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.dashboard.dto.DashboardCommissionResponse;
import com.susukkang.fgc.dashboard.dto.DashboardSummaryResponse;
import com.susukkang.fgc.dashboard.dto.DashboardSummaryResult;
import com.susukkang.fgc.dashboard.service.DashboardService;
import com.susukkang.fgc.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/**
 * FGC-UI-DASH-W01(FUN-057) 업무 대시보드 요약 API
 */
@Tag(name = "업무 대시보드", description = "FGC-UI-DASH-W01 요약 집계 조회 API")
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(
            summary = "업무 대시보드 요약 조회 (IF-API-03)",
            description = "기준월의 KPI 6종(위반/주의/재정거래/대사불일치/전표불균형/미처리예외)과 "+
                    "최근 예외 5건, 최근 검증실행 3건을 함께 돌려준다. month 생략 시 이번 달을 기본값으로 쓴다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공",
                    useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "month 형식(yyyy-MM) 오류 (FGC-COMMON-002)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증되지 않은 요청")
    })
    @GetMapping("/summary")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<DashboardSummaryResponse> summary(
            @Parameter(description = "기준월(yyyy-MM). 생략하면 이번 달", example = "2026-07")
            @RequestParam(required = false) String month) {

        DashboardSummaryResult result = dashboardService.summarize(targetMonth(month));

        DashboardSummaryResponse response = DashboardSummaryResponse.from(result);

        return ApiResponse.success(response);
    }

    @Operation(summary = "최근 6개월 수수료 조회",
            description = "선택한 정산월까지 최근 6개월의 확정 지급을 계약 귀속액 기준으로 집계한다. 지급이 없는 월은 0원으로 반환한다.")
    @GetMapping("/monthly-commissions")
    @PreAuthorize(Roles.ANY_ROLE)
    public ApiResponse<DashboardCommissionResponse> monthlyCommissions(
            @RequestParam(required = false) String month) {
        return ApiResponse.success(dashboardService.monthlyCommissions(targetMonth(month)));
    }

    private LocalDate targetMonth(String month) {
        try {
            return (month == null ? YearMonth.now(DateUtil.SEOUL_ZONE) : YearMonth.parse(month)).atDay(1);
        } catch (DateTimeException e) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "month", Map.of("field", "month"), null);
        }
    }
}
