package com.susukkang.fgc.exceptioncase.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionResponse;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchDTO;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchResponse;
import com.susukkang.fgc.exceptioncase.dto.ExceptionOptionsResponse;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionResponse;
import com.susukkang.fgc.exceptioncase.service.ExceptionCaseService;
import com.susukkang.fgc.exceptioncase.service.JournalCorrectionExceptionActionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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
     *
     * <p>status 파라미터가 아예 없으면 워크큐 기본값 OPEN(미처리)으로 조회한다. 빈 값(status=)은 "전체"다.
     * assigneeFilter 는 "(미배정)"까지 한 컨트롤인 화면용 값으로, unassigned 또는 사용자 ID 를 받아
     * 기존 검색조건(unassignedOnly·assignee)으로 변환한다. 기존 파라미터는 그대로 쓸 수 있다.
     */
    @GetMapping
    public ApiResponse<ExceptionCaseSearchResponse> search(
            @ModelAttribute ExceptionCaseSearchDTO criteria,
            @RequestParam(required = false) String assigneeFilter,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        applyAssigneeFilter(criteria, assigneeFilter);
        if (criteria.getStatus() == null) {
            criteria.setStatus(ExceptionStatus.OPEN_FILTER);
        }
        return ApiResponse.success(exceptionCaseService.search(criteria, page, size));
    }

    /** IF-API-43A 예외함 필터 선택지(유형·상세 원인·심각도·담당자·검증월)를 한 번에 돌려준다. */
    @GetMapping("/options")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ExceptionOptionsResponse> options() {
        return ApiResponse.success(exceptionCaseService.options());
    }

    private void applyAssigneeFilter(ExceptionCaseSearchDTO criteria, String assigneeFilter) {
        if (assigneeFilter == null || assigneeFilter.isBlank()) {
            return;
        }
        if ("unassigned".equals(assigneeFilter)) {
            criteria.setUnassignedOnly(true);
            return;
        }
        try {
            criteria.setAssignee(Long.valueOf(assigneeFilter));
        } catch (NumberFormatException e) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002, "assigneeFilter", Map.of("field", "assigneeFilter"), null);
        }
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
