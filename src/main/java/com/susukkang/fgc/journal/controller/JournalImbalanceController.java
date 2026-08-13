package com.susukkang.fgc.journal.controller;

import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.journal.dto.JournalImbalanceResponse;
import com.susukkang.fgc.journal.dto.LedgerImbalanceRow;
import com.susukkang.fgc.journal.mapper.JournalImbalanceMapper;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@Tag(name = "검증원장", description = "복식부기 검증원장 불균형 조회 API")
@RestController
@RequestMapping("/api/v1/journals")
@RequiredArgsConstructor
public class JournalImbalanceController {

    private final JournalImbalanceMapper journalImbalanceMapper;

    @GetMapping("/imbalances")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<JournalImbalanceResponse>> findImbalances(
            @Parameter(description = "검증 실행 ID") @RequestParam Long validationRunId
    ) {
        List<LedgerImbalanceRow> ledgerImbalanceRows = journalImbalanceMapper.findImbalances(validationRunId);

        List<JournalImbalanceResponse> responses = new ArrayList<>();
        for (LedgerImbalanceRow row : ledgerImbalanceRows) {
            responses.add(JournalImbalanceResponse.from(row));
        }
        return ApiResponse.success(responses);
    }
}
