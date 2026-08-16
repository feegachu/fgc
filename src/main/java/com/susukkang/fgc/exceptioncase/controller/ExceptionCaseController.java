package com.susukkang.fgc.exceptioncase.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionResponse;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchDTO;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchResponse;
import com.susukkang.fgc.exceptioncase.service.ExceptionCaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** FGC-UI-EXCP-W01 예외함 API Controller. */
@RestController
@RequestMapping("/api/v1/exceptions")
@RequiredArgsConstructor
public class ExceptionCaseController {

    private final ExceptionCaseService exceptionCaseService;

    /**
     * 유형·심각도·상태·담당자·계약번호로 예외를 검색한다.
     * 응답 항목에 처리 패널 기본정보와 시간순 action 이력을 함께 포함한다.
    */
    @GetMapping
    public ApiResponse<ExceptionCaseSearchResponse> search(
            @ModelAttribute ExceptionCaseSearchDTO criteria,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(exceptionCaseService.search(criteria, page, size));
    }
    @PreAuthorize("hasAnyRole('SETTLEMENT', 'GA_ADMIN', 'SYSTEM_ADMIN')")
    @PostMapping("/{id}/actions")
    public ApiResponse<ExceptionActionResponse> action(
            @PathVariable Long id,
            @Valid @RequestBody ExceptionActionRequest request,
            @AuthenticationPrincipal FgcUserDetails principal) {
        return ApiResponse.success(exceptionCaseService.action(
                id,
                request,
                principal.getUserId(),
                principal.getUsername()));
    }
}
