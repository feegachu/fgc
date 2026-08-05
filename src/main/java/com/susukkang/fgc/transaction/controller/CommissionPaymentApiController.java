package com.susukkang.fgc.transaction.controller;

import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentUpdateRequest;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "수수료 지급 건", description = "FUN-065 수수료 지급 건 등록·수정·확정 API")
@RestController
@RequestMapping("/api/commission-payments")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SETTLEMENT')")
public class CommissionPaymentApiController {

    private final CommissionPaymentService commissionPaymentService;

    @Operation(summary = "수수료 지급 건 DRAFT 등록")
    @PostMapping
    public ResponseEntity<ApiResponse<CommissionPaymentResponse>> create(
            @Valid @RequestBody CommissionPaymentCreateRequest request
    ) {
        CommissionPaymentResponse response = commissionPaymentService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @Operation(summary = "DRAFT 수수료 지급 건 수정")
    @PutMapping("/{paymentId}")
    public ApiResponse<CommissionPaymentResponse> update(
            @Parameter(description = "지급 건 ID", required = true)
            @PathVariable Long paymentId,
            @Valid @RequestBody CommissionPaymentUpdateRequest request
    ) {
        return ApiResponse.success(
                commissionPaymentService.update(paymentId, request)
        );
    }

    @Operation(
            summary = "수수료 지급 건 확정",
            description = "FUN-033 사전 한도 검증을 수행하고 성공한 DRAFT만 CONFIRMED로 전환합니다."
    )
    @PostMapping("/{paymentId}/confirm")
    public ApiResponse<CommissionPaymentResponse> confirm(
            @Parameter(description = "지급 건 ID", required = true)
            @PathVariable Long paymentId
    ) {
        return ApiResponse.success(commissionPaymentService.confirm(paymentId));
    }
}
