package com.susukkang.fgc.transaction.controller;

import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentBatchRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.service.CommissionPaymentBatchService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/transactions/batch")
public class CommissionPaymentBatchController {
    private final CommissionPaymentBatchService service;

    @PostMapping
    @io.swagger.v3.oas.annotations.Operation(operationId = "createContractPaymentBatch", summary = "계약별 지급 초안 일괄 등록")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<List<CommissionPaymentResponse>> create(@Valid @RequestBody CommissionPaymentBatchRequest request) {
        return ApiResponse.success(service.create(request));
    }
}
