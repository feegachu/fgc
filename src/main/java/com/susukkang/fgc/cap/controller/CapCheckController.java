package com.susukkang.fgc.cap.controller;

import com.susukkang.fgc.cap.dto.CapCheckSearchCriteria;
import com.susukkang.fgc.cap.dto.CapCheckSearchResponse;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

/**
 * FGC-FUN-030 초년도 1,200% 한도 계산 목록 조회 API — CAP-W01(목록).
 *
 * 이 컨트롤러는 FUN-030 IF-API-30만 다룸
 * 목록 응답의 capCheckId로 그 API를 연결하면 된다.
 */
@Tag(name = "1200% 한도", description = "초년도 모집수수료 한도 계산 목록 조회 API")
@RestController
@RequestMapping("/api/cap/checks")
@RequiredArgsConstructor
public class CapCheckController {

    private final CapCheckService capCheckService;

    @Operation(
            summary = "1,200% 한도 판정 목록 조회 (IF-API-30)",
            description = "정산월·지급단계·판정·보험회사·계약번호로 검색하고, 요약 카드 4장(정상/주의/위반/검토필요)과 "
                    + "함께 페이징된 목록을 돌려준다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = CapCheckSearchResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "page<1, size가 1~100 범위 밖, month 형식(yyyy-MM) 오류 등 (FGC-COMMON-002)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "인증되지 않은 요청")
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<CapCheckSearchResponse> search(
            @Parameter(description = "정산월(yyyy-MM). as_of_date가 속한 달로 거른다", example = "2026-07")
            @RequestParam(required = false) String month,
            @Parameter(description = "지급단계") @RequestParam(required = false) PaymentStage stage,
            @Parameter(description = "판정") @RequestParam(required = false) CapResultStatus status,
            @Parameter(description = "보험회사 ID") @RequestParam(required = false) Long insurerId,
            @Parameter(description = "계약번호") @RequestParam(required = false) String contractNo,
            @Parameter(description = "페이지(1-base)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "페이지 크기(최대 100)") @RequestParam(defaultValue = "20") int size
    ) {
        CapCheckSearchCriteria criteria = new CapCheckSearchCriteria(
                month == null ? null : YearMonth.parse(month).atDay(1),
                stage == null ? null : stage.name(),
                status == null ? null : status.name(),
                insurerId,
                contractNo);

        return ApiResponse.success(CapCheckSearchResponse.from(capCheckService.search(criteria, page, size)));
    }
}
