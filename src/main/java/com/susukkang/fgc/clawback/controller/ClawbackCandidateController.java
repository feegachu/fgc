package com.susukkang.fgc.clawback.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.clawback.dto.ClawbackCalculationRequest;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateDetailResponse;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateResponse;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateSearchCondition;
import com.susukkang.fgc.clawback.service.ClawbackCalculationService;
import com.susukkang.fgc.clawback.service.ClawbackCandidateQueryService;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

/** IF-API-83·84 내부 환수 검토 조회와 IF-API-85 계산. 설계사 본인 화면에는 개방하지 않는다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/clawbacks/candidates")
@PreAuthorize(Roles.ANY_ROLE)
public class ClawbackCandidateController {
    private final ClawbackCandidateQueryService service;
    private final ClawbackCalculationService calculationService;

    @GetMapping
    @Operation(operationId = "searchClawbackCandidates", summary = "IF-API-83 환수 검토 후보 검색")
    public ApiResponse<PageResponse<ClawbackCandidateResponse>> search(
            @ParameterObject @ModelAttribute ClawbackCandidateSearchCondition condition,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.search(condition, page, size));
    }

    @GetMapping("/{candidateId}")
    @Operation(operationId = "getClawbackCandidate", summary = "IF-API-84 환수 후보 원천·지급·보류 근거 조회")
    public ApiResponse<ClawbackCandidateDetailResponse> detail(@PathVariable long candidateId) {
        return ApiResponse.success(service.detail(candidateId));
    }

    @PostMapping("/{candidateId}/calculations")
    @PreAuthorize(Roles.CAN_CALCULATE_CLAWBACK)
    @Operation(operationId = "calculateClawbackCandidate", summary = "IF-API-85 환수액 계산",
            description = "선택한 정책버전과 서버가 잠근 원천으로 계산판을 만듭니다. 최신 판과 원천·정책·산식이 같으면 "
                    + "그 판을 반환하고, 같은 Idempotency-Key 재요청은 최초 결과를 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "계산판", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "원천 버전 변경 또는 멱등키 충돌"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "적용 정책·계산 근거 부족")
    })
    public ResponseEntity<ApiResponse<ClawbackCandidateDetailResponse.Calculation>> calculate(
            @PathVariable long candidateId,
            @Valid @RequestBody ClawbackCalculationRequest request,
            @AuthenticationPrincipal FgcUserDetails principal,
            @Parameter(description = "계산 요청의 멱등키", required = true)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        var calculation = calculationService.calculate(candidateId, request, idempotencyKey, principal.getUserId());
        return ResponseEntity.created(URI.create("/api/v1/clawbacks/candidates/" + candidateId))
                .body(ApiResponse.success(calculation));
    }
}
