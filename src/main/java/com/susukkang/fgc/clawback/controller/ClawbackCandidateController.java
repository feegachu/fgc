package com.susukkang.fgc.clawback.controller;

import com.susukkang.fgc.clawback.dto.ClawbackCandidateDetailResponse;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateResponse;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateSearchCondition;
import com.susukkang.fgc.clawback.service.ClawbackCandidateQueryService;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** IF-API-83·84 내부 환수 검토 조회. 설계사 본인 화면에는 개방하지 않는다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/clawbacks/candidates")
@PreAuthorize(Roles.ANY_ROLE)
public class ClawbackCandidateController {
    private final ClawbackCandidateQueryService service;

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
}
