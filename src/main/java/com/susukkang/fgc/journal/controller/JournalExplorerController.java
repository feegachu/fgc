package com.susukkang.fgc.journal.controller;

import com.susukkang.fgc.common.code.JournalHeaderStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.journal.domain.JournalType;
import com.susukkang.fgc.journal.dto.JournalExplorer;
import com.susukkang.fgc.journal.repository.JournalExplorerRepository;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/journals/explorer")
@RequiredArgsConstructor
@Validated
public class JournalExplorerController {
    private final JournalExplorerRepository repository;
    @GetMapping
    @Operation(summary="계약별 검증원장 탐색", description="계약별 분개 건수와 차변·대변을 페이지 단위로 조회한다. contractId=0은 공통·미연결이다.")
    @PreAuthorize("hasAnyRole('SETTLEMENT','GA_ADMIN','COMPLIANCE','SYSTEM_ADMIN')")
    public ApiResponse<JournalExplorer> explore(
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) JournalType type, @RequestParam(required=false) @Size(max=60) String account,
            @RequestParam(required=false) @Size(max=80) String contractNo, @RequestParam(required=false) JournalHeaderStatus status,
            @RequestParam(required=false) @Min(0) Long contractId, @RequestParam(defaultValue="false") boolean all,
            @RequestParam(required=false) @Min(1) Long journalId,
            @RequestParam(defaultValue="1") @Min(1) int contractPage,
            @RequestParam(defaultValue="1") @Min(1) int page, @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        if (from != null && to != null && from.isAfter(to))
            throw new FgcBusinessException(FgcErrorCode.COMMON_002,"to",Map.of("field","to"),null);
        return ApiResponse.success(repository.explore(new JournalExplorer.Filter(from,to,type == null ? null : type.name(),
                account,contractNo,status == null ? null : status.name()),contractId,all,journalId,contractPage,page,size));
    }
}
