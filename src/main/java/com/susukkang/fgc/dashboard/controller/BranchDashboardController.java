package com.susukkang.fgc.dashboard.controller;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.dashboard.dto.BranchDashboardResponse;
import com.susukkang.fgc.dashboard.dto.BranchDetailRow;
import com.susukkang.fgc.dashboard.dto.BranchMetric;
import com.susukkang.fgc.dashboard.repository.BranchDashboardRepository;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@PreAuthorize(Roles.ANY_ROLE)
public class BranchDashboardController {
    private final BranchDashboardRepository repository;

    @GetMapping("/branches")
    @Operation(operationId = "dashboardBranchSummary", summary = "지점별 인원·계약·수수료·예외 현황", description = "하위 팀을 지점에 포함한다. 인원·유효 계약은 정산월 말(미래는 오늘) 기준, 수수료·신규 계약은 정산월, 미처리 예외는 현재 상태 기준이다.")
    public ApiResponse<BranchDashboardResponse> summary(@RequestParam String month) {
        return ApiResponse.success(repository.summary(parseMonth(month)));
    }

    @GetMapping("/branch-details")
    @Operation(operationId = "dashboardBranchDetails", summary = "선택한 지점의 지표별 상세 내역")
    public ApiResponse<PageResponse<BranchDetailRow>> details(@RequestParam String month, @RequestParam long organizationId,
            @RequestParam BranchMetric metric, @RequestParam(defaultValue = "1") int page) {
        if (organizationId < 1) throw invalid("organizationId");
        if (page < 1) throw invalid("page");
        return ApiResponse.success(repository.details(parseMonth(month), organizationId, metric, page));
    }

    private LocalDate parseMonth(String month) {
        try { return YearMonth.parse(month).atDay(1); }
        catch (DateTimeException e) { throw invalid("month"); }
    }
    private FgcBusinessException invalid(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }
}
