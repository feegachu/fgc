package com.susukkang.fgc.reconciliation.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.reconciliation.dto.*;
import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;
import com.susukkang.fgc.reconciliation.service.ReconciliationExceptionService;
import com.susukkang.fgc.reconciliation.service.ReconciliationResultQueryService;
import com.susukkang.fgc.reconciliation.service.ReconciliationRunHistoryService;
import com.susukkang.fgc.reconciliation.service.ReconciliationRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final ReconciliationResultQueryService reconciliationResultQueryService;
    private final ReconciliationRunHistoryService reconciliationRunHistoryService;
    private final ReconciliationExceptionService reconciliationExceptionService;

    @Operation(summary = "대사 실행 이력 조회 (IF-API-39)")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ReconciliationRunSearchResponse> searchHistory(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) Long insurerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        LocalDate settlementMonth = null;
        if (month != null) {
            settlementMonth = parseSettlementMonth(month);
        }

        String paymentStage = null;
        if (stage != null) {
            paymentStage = parsePaymentStage(stage).name();
        }

        ReconciliationRunSearchCriteria criteria =
                new ReconciliationRunSearchCriteria(settlementMonth, paymentStage, insurerId);
        PageResponse<ReconciliationRunHistoryResponse> result = reconciliationRunHistoryService.findHistory(criteria, page, size, sort);

        return ApiResponse.success(ReconciliationRunSearchResponse.from(result));
    }

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

    @Operation(summary = "대사 결과 목록 조회 (IF-API-40)")
    @GetMapping("/{reconciliationRunId}/results")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ReconciliationResultSearchResponse> searchResults(
            @PathVariable long reconciliationRunId,
            @RequestParam(required = false) String resultType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        if (resultType != null) {
            try {
                ReconciliationResultType.valueOf(resultType);
            } catch (IllegalArgumentException exception) {
                invalid("resultType");
            }
        }
        return ApiResponse.success(reconciliationResultQueryService.search(
                reconciliationRunId, resultType, page, size, sort));
    }

    @Operation(summary = "불일치 예외 일괄 생성 (IF-API-42)")
    @PostMapping("/{reconciliationRunId}/exceptions")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<ReconciliationExceptionBulkCreateResponse> bulkCreateExceptions(
            @PathVariable Long reconciliationRunId
    ) {
        return ApiResponse.success(reconciliationExceptionService.bulkCreate(reconciliationRunId));
    }

    @Operation(summary = "대사 결과 상세 조회 (IF-API-41)")
    @GetMapping("/results/{reconciliationResultId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ReconciliationResultDetailResponse> getResult(
            @PathVariable long reconciliationResultId
    ) {
        return ApiResponse.success(reconciliationResultQueryService.get(reconciliationResultId));
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
