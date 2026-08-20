package com.susukkang.fgc.journal.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.journal.dto.JournalCorrectionResult;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionRequest;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionResponse;
import com.susukkang.fgc.journal.dto.ReverseJournalCommand;
import com.susukkang.fgc.journal.dto.ReverseJournalRequest;
import com.susukkang.fgc.journal.dto.ReverseJournalResponse;
import com.susukkang.fgc.journal.service.JournalCorrectionService;
import com.susukkang.fgc.journal.service.JournalCorrectionExceptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 설명 : IF-API-36·36A 원장 역분개 및 정정 예외 생성 API
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
@RestController
@RequestMapping("/api/v1/journals")
@RequiredArgsConstructor
public class JournalCorrectionController {

    private final JournalCorrectionService journalCorrectionService;
    private final JournalCorrectionExceptionService journalCorrectionExceptionService;

    @PreAuthorize(Roles.CAN_REVERSE_JOURNAL)
    @PostMapping("/{journalId}/reverse")
    public ApiResponse<ReverseJournalResponse> reverse(
            @PathVariable Long journalId,
            @Valid @RequestBody ReverseJournalRequest request,
            @AuthenticationPrincipal FgcUserDetails principal) {
        JournalCorrectionResult result = journalCorrectionService.reverse(
                new ReverseJournalCommand(
                        journalId, request.reason(), request.evidenceRef(), principal.getUserId()));
        return ApiResponse.success(ReverseJournalResponse.from(result));
    }

    /** IF-API-36A 원분개 정정 예외를 생성하거나 기존 업무건을 반환한다. */
    @PreAuthorize(Roles.CAN_REVERSE_JOURNAL)
    @PostMapping("/{journalId}/correction-exceptions")
    public ApiResponse<JournalCorrectionExceptionResponse> createCorrectionException(
            @PathVariable Long journalId,
            @Valid @RequestBody JournalCorrectionExceptionRequest request,
            @AuthenticationPrincipal FgcUserDetails principal) {
        return ApiResponse.success(journalCorrectionExceptionService.createOrGet(
                journalId, request, principal.getUserId()));
    }
}
