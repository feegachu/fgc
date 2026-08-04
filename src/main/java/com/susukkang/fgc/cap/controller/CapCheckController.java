package com.susukkang.fgc.cap.controller;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCheckResponse;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.cap.dto.CapCheckTriggerRequest;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * FGC-FUN-030 초년도 1,200% 한도 계산의 실시간 API(#3).
 * 업무 로직은 CapCheckService/CapCalculator 에 있고, 이 컨트롤러는 요청·응답 변환만 한다.
 *
 * ★ 계약 등록·수정 이벤트와의 실제 연동은 이 PR 범위가 아니다. Contract 도메인이 아직 없어서
 *   (코드베이스 전체에 계약 등록·수정 API가 없음), 지금은 이 트리거 엔드포인트만 만들어 두고
 *   Contract 모듈이 생기면 그쪽에서 이 서비스(또는 이 API)를 호출하도록 한다.
 */
@Tag(name = "1200% 한도", description = "초년도 모집수수료 한도 계산 API")
@RestController
@RequestMapping("/api/cap/checks")
@RequiredArgsConstructor
public class CapCheckController {

    private final CapCheckService capCheckService;

    @Operation(
            summary = "초년도 1,200% 한도 계산 실행",
            description = "계약 1건·지급단계 1개에 대해 한도를 계산하고 cap_check/cap_check_detail 에 저장한다. "
                    + "표나 12차월 값이 없어 자동 계산이 불가능해도(REVIEW_REQUIRED) 이는 에러가 아니라 유효한 "
                    + "계산 결과이므로 200으로 응답하며, 클라이언트는 resultStatus 필드로 구분한다."
    )
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<CapCheckResponse> calculate(@Valid @RequestBody CapCheckTriggerRequest request) {
        LocalDate asOfDate = request.asOfDate() != null ? request.asOfDate() : LocalDate.now();
        CapCalculationCommand command = CapCalculationCommand.realtime(
                request.contractId(), request.paymentStage(), asOfDate);

        CapCheckSaveResult saved = capCheckService.calculateAndSave(command);
        return ApiResponse.success(CapCheckResponse.from(saved));
    }

    @Operation(
            summary = "최근 저장된 1,200% 판정 결과 조회",
            description = "재계산하지 않고, 가장 최근에 저장된 cap_check 스냅샷을 그대로 돌려준다."
    )
    @GetMapping("/{contractId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<CapCheckResponse> findLatest(
            @Parameter(description = "계약 ID", example = "1")
            @PathVariable Long contractId,
            @Parameter(description = "지급단계", example = "GA_TO_FC")
            @RequestParam PaymentStage paymentStage
    ) {
        CapCheckSaveResult saved = capCheckService.findLatest(contractId, paymentStage)
                .orElseThrow(() -> new FgcBusinessException(FgcErrorCode.CAP_005,
                        Map.of("contractId", contractId, "paymentStage", paymentStage)));
        return ApiResponse.success(CapCheckResponse.from(saved));
    }
}
