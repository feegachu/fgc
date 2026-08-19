package com.susukkang.fgc.journal.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.journal.dto.JournalCorrectionResult;
import com.susukkang.fgc.journal.dto.ReverseJournalCommand;
import com.susukkang.fgc.journal.dto.ReverseJournalRequest;
import com.susukkang.fgc.journal.dto.ReverseJournalResponse;
import com.susukkang.fgc.journal.service.JournalCorrectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** IF-API-36 원장 역분개 API. */
@RestController
@RequestMapping("/api/v1/journals")
@RequiredArgsConstructor
public class JournalCorrectionController {

    private final JournalCorrectionService journalCorrectionService;

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
}
