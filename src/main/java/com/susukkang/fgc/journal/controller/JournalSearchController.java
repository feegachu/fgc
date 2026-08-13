package com.susukkang.fgc.journal.controller;

import com.susukkang.fgc.common.code.JournalHeaderStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.journal.domain.JournalType;
import com.susukkang.fgc.journal.dto.JournalListRow;
import com.susukkang.fgc.journal.dto.JournalSearchCriteria;
import com.susukkang.fgc.journal.dto.JournalSearchResponse;
import com.susukkang.fgc.journal.service.JournalSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Map;

/**
 * LEDG-W01 검증원장 목록 조회 — GET /api/v1/journals.
 */
@Tag(name = "검증원장", description = "검증원장 목록 조회 API")
@RestController
@RequestMapping("/api/v1/journals")
@RequiredArgsConstructor
public class JournalSearchController {

    private final JournalSearchService journalSearchService;

    @Operation(summary = "검증원장 목록 조회",
            description = "기간(from/to)·분개유형·계정·계약·상태로 검색하고 페이징된 목록을 돌려준다.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<JournalSearchResponse> search(
            @Parameter(description = "분개일 시작(yyyy-MM-dd)") @RequestParam(required = false) String from,
            @Parameter(description = "분개일 종료(yyyy-MM-dd)") @RequestParam(required = false) String to,
            // IF-API-34 쿼리 파라미터명(type/account/contract)을 그대로 따른다
            // (docs/05_인터페이스정의서_v2_0.md:305).
            @Parameter(description = "분개유형(EXPECTED_INSURER_INCOME 등 8종)")
            @RequestParam(name = "type", required = false) String journalType,
            @Parameter(description = "계정코드") @RequestParam(name = "account", required = false) String accountCode,
            @Parameter(description = "계약 ID") @RequestParam(name = "contract", required = false) Long contractId,
            @Parameter(description = "상태(DRAFT/POSTED/REVERSED)") @RequestParam(required = false) String status,
            @Parameter(description = "페이지(1-base)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "페이지 크기(최대 100)") @RequestParam(defaultValue = "20") int size
    ) {
        // from/to는 서로 독립적인 필터라 각각 있을 때만 파싱
        LocalDate journalFromDate = null;
        if (from != null) {
            try {
                journalFromDate = LocalDate.parse(from);
            } catch (DateTimeException e) {
                throw new FgcBusinessException(FgcErrorCode.COMMON_002, "from", Map.of("field", "from"), null);
            }
        }

        LocalDate journalToDate = null;
        if (to != null) {
            try {
                journalToDate = LocalDate.parse(to);
            } catch (DateTimeException e) {
                throw new FgcBusinessException(FgcErrorCode.COMMON_002, "to", Map.of("field", "to"), null);
            }
        }

        if(journalType!=null){
            try {
                JournalType.valueOf(journalType);
            } catch (IllegalArgumentException e){
                throw new FgcBusinessException(FgcErrorCode.COMMON_002, "journalType", Map.of("field", "journalType"), null);
            }
        }

        if(status!=null){
            try {
                JournalHeaderStatus.valueOf(status);
            } catch (IllegalArgumentException e){
                throw new FgcBusinessException(FgcErrorCode.COMMON_002, "status", Map.of("field", "status"), null);
            }
        }

        JournalSearchCriteria criteria = new JournalSearchCriteria(
                journalFromDate, journalToDate, journalType, accountCode, contractId, status);
        PageResponse<JournalListRow> pageResponse = journalSearchService.search(criteria, page, size);
        return ApiResponse.success(JournalSearchResponse.from(pageResponse));
    }
}
