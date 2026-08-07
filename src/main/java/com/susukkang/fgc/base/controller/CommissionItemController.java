package com.susukkang.fgc.base.controller;

import com.susukkang.fgc.base.dto.CommissionItemResponse;
import com.susukkang.fgc.base.service.CommissionItemService;
import com.susukkang.fgc.common.web.ApiResponse;
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
