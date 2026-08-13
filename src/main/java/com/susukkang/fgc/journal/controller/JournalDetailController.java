package com.susukkang.fgc.journal.controller;

import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.journal.dto.JournalDetailResponse;
import com.susukkang.fgc.journal.service.JournalDetailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LEDG-W01 검증원장 상세 조회(IF-API-35) — GET /api/v1/journals/{journalHeaderId}.
 */
@Tag(name = "검증원장", description = "검증원장 상세 조회 API")
@RestController
@RequestMapping("/api/v1/journals")
@RequiredArgsConstructor
public class JournalDetailController {

    private final JournalDetailService journalDetailService;

    @Operation(summary = "검증원장 상세 조회",
            description = "분개 헤더 + 라인 목록 + 차변/대변 합계·차액·균형 여부를 반환한다.")
    @GetMapping("/{journalHeaderId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<JournalDetailResponse> findByJournalHeaderId(@PathVariable Long journalHeaderId) {
        // 존재하지 않는 ID는 Service가 FgcBusinessException(COMMON_004)을 던지고
        // GlobalExceptionHandler가 이를 404로 변환하므로 여기서 따로 null 체크하지 않는다.
        return ApiResponse.success(journalDetailService.findByJournalHeaderId(journalHeaderId));
    }
}
