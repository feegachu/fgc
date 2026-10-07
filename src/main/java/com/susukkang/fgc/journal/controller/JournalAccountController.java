package com.susukkang.fgc.journal.controller;

import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.journal.dto.JournalAccountRow;
import com.susukkang.fgc.journal.service.JournalAccountCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 원장 정정 모달(EXCP-W01) 등 원장 입력 화면이 쓰는 활성 계정과목 목록 — GET /api/v1/journals/accounts.
 */
@Tag(name = "검증원장", description = "분개 계정과목 기준정보 API")
@RestController
@RequestMapping("/api/v1/journals")
@RequiredArgsConstructor
public class JournalAccountController {

    private final JournalAccountCatalogService journalAccountCatalogService;

    @Operation(summary = "활성 분개 계정과목 목록 조회",
            description = "사용 중인 계정과목을 계정코드 오름차순으로 돌려준다.")
    @GetMapping("/accounts")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<JournalAccountRow>> accounts() {
        return ApiResponse.success(journalAccountCatalogService.findAllActive());
    }
}
