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
    @Operation(summary = "저장된 지급의 읽기 전용 계산 스냅샷", description = "기존 조회·가상 후보 계산. 서버 계산 증표를 발급하지만 실제 사전검증·확정 증표와 구분. 금융 상태 변경 없음.")
    public ApiResponse<PaymentCalculationSnapshot> calculate(@Valid @RequestBody PaymentCalculationRequest request) {
        return ApiResponse.success(service.calculateVerified(request));
    }
    @PostMapping("/revalidate")
    @Operation(summary="계산 입력·의존성·사용자·만료 재검증",description="변경/만료는 STALE로 반환하고 추천을 차단합니다. 증표 변조는 409, 다른 소유자는 403입니다.")
    public ApiResponse<PaymentCalculationSnapshot> revalidate(@Valid @RequestBody com.susukkang.fgc.assistant.dto.CalculationRevalidationRequest request) {return ApiResponse.success(service.revalidate(request));}
}
