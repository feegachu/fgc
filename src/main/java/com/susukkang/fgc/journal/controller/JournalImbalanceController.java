package com.susukkang.fgc.journal.controller;

import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.journal.dto.JournalImbalanceSearchResponse;
import com.susukkang.fgc.journal.service.JournalImbalanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "검증원장", description = "복식부기 검증원장 불균형 조회 API")
@RestController
@RequestMapping("/api/v1/journals")
@RequiredArgsConstructor
public class JournalImbalanceController {

    private final JournalImbalanceService journalImbalanceService;

    @Operation(summary = "검증 실행 불균형 분개 조회",
            description = "차변 합계와 대변 합계가 일치하지 않는 분개를 검증 실행 범위로 조회한다(IF-API-37).")
    @GetMapping("/imbalances")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<JournalImbalanceSearchResponse> findImbalances(
            @Parameter(description = "검증 실행 ID") @RequestParam Long validationRunId
    ) {
        return ApiResponse.success(journalImbalanceService.findImbalances(validationRunId));
    }
}
