package com.susukkang.fgc.policy.controller;

import com.susukkang.fgc.common.code.PolicyStatus;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.policy.dto.PolicyDetailResponse;
import com.susukkang.fgc.policy.dto.PolicyVersionResponse;
import com.susukkang.fgc.policy.service.PolicyQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : POL-W01 정책·룰셋 조회 API (FGC-FUN-012·013).
 * 1차는 조회 전용이다 — 요율·한도를 요청으로 받는 API 는 만들지 않는다(COR-004).
 */
@Tag(name = "정책·룰셋", description = "FGC 정책·룰셋 조회 API — 1차 조회 전용, 편집은 2차(POL-W02)")
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private final PolicyQueryService policyQueryService;

    public PolicyController(PolicyQueryService policyQueryService) {
        this.policyQueryService = policyQueryService;
    }

    @Operation(
            summary = "정책 버전 목록 조회 (IF-API-09)",
            description = "정책 유형·기준일·상태로 정책 버전 목록을 조회합니다. "
                    + "기준일(asOf)은 해당 일자에 유효한 버전만 남깁니다 — 정책은 계약일 기준으로 고르는 경우가 많습니다. "
                    + "조건에 맞는 자료가 없으면 200과 빈 배열을 반환합니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<PolicyVersionResponse>> findPolicyVersions(
            @Parameter(description = "정책 유형", example = "CAP_1200")
            @RequestParam(required = false)
            PolicyType type,
            @Parameter(description = "기준일(yyyy-MM-dd) — 해당 일자에 유효한 버전만 조회", example = "2026-07-01")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf,
            @Parameter(description = "정책 상태", example = "ACTIVE")
            @RequestParam(required = false)
            PolicyStatus status
    ) {
        return ApiResponse.success(policyQueryService.findPolicyVersions(type, asOf, status));
    }

    @Operation(
            summary = "정책 버전 상세 조회 (IF-API-10)",
            description = "정책 버전 1건의 상세를 POL-W01 탭 구성(수수료 규칙, 1,200% 룰셋·항목별 산입 판정, "
                    + "예상 해약환급률표, 근거 참조)으로 조회합니다. 탭 클릭 시 Ajax 로 호출됩니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = PolicyDetailResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "정책 버전 없음 (FGC-COMMON-004)")
    })
    @GetMapping("/{policyVersionId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PolicyDetailResponse> findPolicyDetail(
            @Parameter(description = "정책 버전 ID", example = "1")
            @PathVariable Long policyVersionId
    ) {
        return ApiResponse.success(policyQueryService.findPolicyDetail(policyVersionId));
    }
}
