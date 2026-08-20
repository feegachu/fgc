package com.susukkang.fgc.exceptioncase.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionResponse;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchDTO;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchResponse;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionResponse;
import com.susukkang.fgc.exceptioncase.service.ExceptionCaseService;
import com.susukkang.fgc.exceptioncase.service.JournalCorrectionExceptionActionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 설명 : FGC-UI-EXCP-W01 예외함 조회·조치·원장 정정 API
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
@RestController
@RequestMapping("/api/v1/exceptions")
@RequiredArgsConstructor
public class ExceptionCaseController {

    private final ExceptionCaseService exceptionCaseService;
    private final JournalCorrectionExceptionActionService journalCorrectionExceptionActionService;

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
    @PreAuthorize(Roles.CAN_HANDLE_EXCEPTION)
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

    /** IF-API-44A 원장 정정 예외의 역분개·재기표와 종결을 함께 수행한다. */
    @PreAuthorize(Roles.CAN_HANDLE_EXCEPTION)
    @PostMapping("/{id}/journal-correction")
    public ApiResponse<JournalCorrectionActionResponse> journalCorrection(
            @PathVariable Long id,
            @Valid @RequestBody JournalCorrectionActionRequest request,
            @AuthenticationPrincipal FgcUserDetails principal) {
        return ApiResponse.success(journalCorrectionExceptionActionService.correct(
                id, request, principal.getUserId(), principal.getUsername()));
    }
}
