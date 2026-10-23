package com.susukkang.fgc.base.controller;

import com.susukkang.fgc.base.dto.CommissionItemResponse;
import com.susukkang.fgc.base.service.CommissionItemService;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "기준정보", description = "FGC 기준정보 조회 API")
@RestController
@RequestMapping("/api/v1/base/commission-items")
public class CommissionItemController {

    private final CommissionItemService commissionItemService;

    public CommissionItemController(CommissionItemService commissionItemService) {
        this.commissionItemService = commissionItemService;
    }

    @Operation(summary = "수수료 항목 검색형 선택", description = "기존 asOf 목록 응답은 유지하고 검색·페이지·선택 ID 재조회를 제공합니다.")
    @GetMapping("/search")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResponse<CommissionItemResponse>> search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String cashflowType,
            @RequestParam(required = false) Long selectedId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(commissionItemService.search(asOf, keyword, cashflowType, selectedId, page, size));
    }

    @Operation(
            summary = "수수료 항목 기준정보 조회·선택",
            description = "기준일자에 사용 가능한 수수료 항목과 사용기간을 조회합니다. "
                    + "1,200% 산입 여부는 룰셋별 cap_rule_item에서 결정합니다."
    )
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<CommissionItemResponse>> findCommissionItems(
            @Parameter(description = "기준일자(yyyy-MM-dd)", example = "2026-08-04", required = true)
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf
    ) {
        return ApiResponse.success(commissionItemService.findEffectiveItems(asOf));
    }
}
