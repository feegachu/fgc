package com.susukkang.fgc.report.controller;

import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.report.dto.StatementDetail;
import com.susukkang.fgc.report.dto.StatementListResponse;
import com.susukkang.fgc.report.service.AgentStatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RPT-W02 설계사 수수료 명세 조회(IF-API-87·88). 본인 경로는 AGENT, 내부 경로는 AGENT 가 아닌 역할이다.
 * 본인 경로에는 agentId 를 받지 않고 인증 연결에서 정한다.
 */
@Tag(name = "설계사 수수료 명세", description = "FUN-060 지급·보류·조정·환수 명세 조회 API")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AgentStatementController {

    private static final String MONTH = "기준월(yyyy-MM). 유형마다 지급 귀속월·보류 예정월·조정 처리월·환수 계산월로 본다";
    private static final String SORT = "정렬(key,asc|desc). basisDate·entryType·contractNo·commissionItemCode·installmentNo·statusCode·amount. 비우면 기준일 내림차순";

    private final AgentStatementService service;

    @Operation(summary = "본인 수수료 명세 목록", description = "지급·보류·조정·환수 내역과 같은 필터의 유형별 합계를 돌려줍니다.")
    @GetMapping("/agent/me/statements")
    @PreAuthorize(Roles.IS_AGENT)
    public ApiResponse<StatementListResponse> mine(
            @Parameter(description = MONTH, example = "2026-07") @RequestParam(required = false) String month,
            @Parameter(description = "계약번호(부분 일치)") @RequestParam(required = false) String contractNo,
            @Parameter(description = "수수료 항목 ID") @RequestParam(required = false) Long commissionItemId,
            @Parameter(description = "내역 구분: PAYMENT/HELD/ADJUSTMENT/CLAWBACK") @RequestParam(required = false) String entryType,
            @Parameter(description = SORT) @RequestParam(required = false) String sort,
            @Parameter(description = "페이지(1-base)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "페이지 크기(최대 100)") @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.listMine(month, contractNo, commissionItemId, entryType, sort, page, size));
    }

    @Operation(summary = "본인 수수료 명세 상세", description = "원천 ID·정책·계산 근거를 돌려줍니다. 남의 명세는 조회되지 않습니다.")
    @GetMapping("/agent/me/statements/{entryKey}")
    @PreAuthorize(Roles.IS_AGENT)
    public ApiResponse<StatementDetail> mineDetail(@PathVariable String entryKey) {
        return ApiResponse.success(service.detailMine(entryKey));
    }

    @Operation(summary = "설계사별 수수료 명세 목록(내부)", description = "AGENT 를 제외한 내부 조회 역할이 설계사를 지정해 조회합니다.")
    @GetMapping("/reports/agents/{agentId}/statements")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<StatementListResponse> forAgent(
            @PathVariable long agentId,
            @Parameter(description = MONTH, example = "2026-07") @RequestParam(required = false) String month,
            @Parameter(description = "계약번호(부분 일치)") @RequestParam(required = false) String contractNo,
            @Parameter(description = "수수료 항목 ID") @RequestParam(required = false) Long commissionItemId,
            @Parameter(description = "내역 구분: PAYMENT/HELD/ADJUSTMENT/CLAWBACK") @RequestParam(required = false) String entryType,
            @Parameter(description = SORT) @RequestParam(required = false) String sort,
            @Parameter(description = "페이지(1-base)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "페이지 크기(최대 100)") @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                service.listForAgent(agentId, month, contractNo, commissionItemId, entryType, sort, page, size));
    }

    @Operation(summary = "설계사별 수수료 명세 상세(내부)")
    @GetMapping("/reports/agents/{agentId}/statements/{entryKey}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<StatementDetail> detailForAgent(@PathVariable long agentId, @PathVariable String entryKey) {
        return ApiResponse.success(service.detailForAgent(agentId, entryKey));
    }
}
