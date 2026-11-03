package com.susukkang.fgc.assistant.controller;

import com.susukkang.fgc.assistant.dto.PaymentCalculationRequest;
import com.susukkang.fgc.assistant.dto.PaymentCalculationSnapshot;
import com.susukkang.fgc.assistant.service.PaymentCalculationService;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/assistant/calculations")
@PreAuthorize(Roles.ANY_ROLE)
public class PaymentCalculationController {
    private final PaymentCalculationService service;
    private final com.susukkang.fgc.common.exception.GlobalExceptionHandler errors;
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<Void>> malformedCalculation() {
        return errors.handleBusinessException(new com.susukkang.fgc.common.exception.FgcBusinessException(
                com.susukkang.fgc.common.exception.FgcErrorCode.COMMON_002, "request", java.util.Map.of("field", "request"), null));
    }
    @PostMapping
    @Operation(summary = "저장된 지급의 읽기 전용 계산 스냅샷", description = "ATTRIBUTION_BALANCE 및 DRAFT PAYMENT_CANDIDATE EXISTING만 지원. validity=null, 추천/가이드 완료 증표 없음. 금융 상태 변경 없음.")
    public ApiResponse<PaymentCalculationSnapshot> calculate(@Valid @RequestBody PaymentCalculationRequest request) {
        return ApiResponse.success(service.calculate(request));
    }
}
