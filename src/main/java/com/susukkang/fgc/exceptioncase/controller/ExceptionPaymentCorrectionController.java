package com.susukkang.fgc.exceptioncase.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.exceptioncase.dto.*;
import com.susukkang.fgc.exceptioncase.service.ExceptionPaymentCorrectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/exceptions/{id}/payment-correction")
@RequiredArgsConstructor
@PreAuthorize(Roles.CAN_HANDLE_EXCEPTION)
public class ExceptionPaymentCorrectionController {
    private final ExceptionPaymentCorrectionService service;
    @GetMapping
    public ApiResponse<ExceptionPaymentContext> context(@PathVariable Long id, @AuthenticationPrincipal FgcUserDetails user) {
        return ApiResponse.success(service.context(id, user.getUserId()));
    }
    @PostMapping
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<ExceptionPaymentCorrectionResponse> correct(@PathVariable Long id,
            @Valid @RequestBody ExceptionPaymentCorrectionRequest request, @AuthenticationPrincipal FgcUserDetails user) {
        return ApiResponse.success(service.correct(id, request, user.getUserId(), user.getUsername()));
    }
    @PostMapping("/precheck")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<com.susukkang.fgc.transaction.dto.TransactionPrecheckResponse> preview(@PathVariable Long id,
            @Valid @RequestBody ExceptionPaymentCorrectionRequest request, @AuthenticationPrincipal FgcUserDetails user) {
        return ApiResponse.success(service.preview(id, request, user.getUserId()));
    }

    @PostMapping("/recheck")
    public ApiResponse<ExceptionSourceRecheckResponse> recheck(@PathVariable Long id, @AuthenticationPrincipal FgcUserDetails user) {
        return ApiResponse.success(service.recheckSchedule(id, user.getUserId(), user.getUsername()));
    }
    @PostMapping("/cancel-duplicate")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<ExceptionPaymentCorrectionResponse> cancelDuplicate(@PathVariable Long id,
            @Valid @RequestBody ExceptionDuplicateCancelRequest request, @AuthenticationPrincipal FgcUserDetails user) {
        return ApiResponse.success(service.cancelDuplicate(id, request, user.getUserId(), user.getUsername()));
    }
}
