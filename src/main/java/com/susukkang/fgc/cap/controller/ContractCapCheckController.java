package com.susukkang.fgc.cap.controller;

import com.susukkang.fgc.cap.dto.CapCheckItemResponse;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CONT-W02(계약 상세) 탭3 전용 — 계약 1건의 지급단계별 1,200% 한도 판정.
 */
@Tag(name = "1200% 한도", description = "계약별 1,200% 한도 판정 조회 API")
@RestController
@RequiredArgsConstructor
public class ContractCapCheckController {

    private final CapCheckService capCheckService;

    @Operation(
            summary = "계약별 1,200% 한도 판정 조회 (IF-API-14)",
            description = "지급단계별(원수사→GA, GA→설계사) 최신 판정을 최대 2건 돌려준다. 두 게이지는 절대 "
                    + "합산하지 않고 각각 그대로 내려준다."
    )
    @GetMapping("/api/contracts/{contractId}/cap/checks")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<CapCheckItemResponse>> findByContract(
            @Parameter(description = "계약 ID", example = "1")
            @PathVariable Long contractId
    ) {
        List<CapCheckItemResponse> items = capCheckService.findByContract(contractId).stream()
                .map(CapCheckItemResponse::from)
                .toList();
        return ApiResponse.success(items);
    }
}
