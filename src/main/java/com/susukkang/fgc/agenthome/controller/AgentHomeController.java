package com.susukkang.fgc.agenthome.controller;

import com.susukkang.fgc.agenthome.dto.AgentHomeResponse;
import com.susukkang.fgc.agenthome.dto.ContractCheckRequest;
import com.susukkang.fgc.agenthome.dto.ContractCheckResponse;
import com.susukkang.fgc.agenthome.service.AgentHomeService;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** AGNT-W01 설계사 홈(IF-API-74·74A). 설계사 ID는 인증 연결에서만 정한다. */
@Tag(name = "설계사 홈", description = "FUN-086 포트폴리오·이번 달 수수료·확인할 계약")
@RestController
@RequestMapping("/api/v1/agent/me")
@RequiredArgsConstructor
public class AgentHomeController {

    private final AgentHomeService service;

    @Operation(summary = "설계사 홈", description = "기간·보험사 필터를 적용한 포트폴리오와 asOf 월 수수료, 확인할 계약을 돌려줍니다.")
    @GetMapping("/home")
    @PreAuthorize(Roles.IS_AGENT)
    public ApiResponse<AgentHomeResponse> home(
            @Parameter(description = "기준일(기본 서울 오늘)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @Parameter(description = "6|12|ALL (기본 12)") @RequestParam(required = false) String period,
            @Parameter(description = "ALL|LIFE|NON_LIFE (기본 ALL)") @RequestParam(required = false) String insurerType,
            @Parameter(description = "보험사 ID") @RequestParam(required = false) Long insurerId) {
        return ApiResponse.success(service.home(asOf, period, insurerType, insurerId));
    }

    @Operation(summary = "계약 확인 기록", description = "종결 또는 후속 확인을 기록합니다. Idempotency-Key 와 expectedContextVersion 이 필요합니다.")
    @PostMapping("/contract-checks")
    @PreAuthorize(Roles.IS_AGENT)
    public ApiResponse<ContractCheckResponse> record(
            @Valid @RequestBody ContractCheckRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.success(service.recordCheck(request, idempotencyKey));
    }
}
