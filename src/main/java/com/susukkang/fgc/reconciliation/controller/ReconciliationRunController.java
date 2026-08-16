package com.susukkang.fgc.reconciliation.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.reconciliation.dto.CreateReconciliationRunCommand;
import com.susukkang.fgc.reconciliation.dto.CreateReconciliationRunRequest;
import com.susukkang.fgc.reconciliation.dto.CreateReconciliationRunResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunRow;
import com.susukkang.fgc.reconciliation.service.ReconciliationRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Map;

/**
 * 설명 : IF-API-38 대사 실행 생성 API
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@Tag(name = "대사", description = "예상 스케줄과 실제 지급 데이터의 대사 실행 API")
@RestController
@RequestMapping("/api/v1/reconciliations")
@RequiredArgsConstructor
public class ReconciliationRunController {

    private final ReconciliationRunService reconciliationRunService;

    @Operation(summary = "대사 실행 생성 (IF-API-38)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<CreateReconciliationRunResponse> create(
            @Valid @RequestBody CreateReconciliationRunRequest request,
            @AuthenticationPrincipal FgcUserDetails principal
    ) {
        LocalDate settlementMonth = parseSettlementMonth(request.settlementMonth());
        PaymentStage paymentStage = parsePaymentStage(request.paymentStage());

        CreateReconciliationRunCommand command = new CreateReconciliationRunCommand(
                settlementMonth,
                paymentStage,
                request.insurerId(),
                request.validationRunId(),
                principal.getUserId()
        );
        ReconciliationRunRow created = reconciliationRunService.create(command);
        return ApiResponse.success(CreateReconciliationRunResponse.from(created));
    }

    private static LocalDate parseSettlementMonth(String value) {
        try {
            return DateUtil.parseSettlementMonth(value);
        } catch (DateTimeException | NullPointerException exception) {
            invalid("settlementMonth");
            return null;
        }
    }

    private static PaymentStage parsePaymentStage(String value) {
        try {
            return PaymentStage.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            invalid("paymentStage");
            return null;
        }
    }

    private static void invalid(String field) {
        throw new FgcBusinessException(
                FgcErrorCode.COMMON_002,
                field,
                Map.of("field", field),
                null
        );
    }
}
