package com.susukkang.fgc.contract.controller;

import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckView;
import com.susukkang.fgc.arbitrage.dto.ReArbitrageCheckRequest;
import com.susukkang.fgc.arbitrage.dto.ReArbitrageCheckResponse;
import com.susukkang.fgc.arbitrage.service.ArbitrageService;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.contract.dto.ContractDetailResponse;
import com.susukkang.fgc.contract.dto.ContractJournalResponse;
import com.susukkang.fgc.contract.dto.ContractScheduleResponse;
import com.susukkang.fgc.contract.dto.ContractStatusEventResponse;
import com.susukkang.fgc.contract.dto.ContractTransactionTabResponse;
import com.susukkang.fgc.contract.service.ContractJournalProjectionService;
import com.susukkang.fgc.contract.service.ContractService;
import com.susukkang.fgc.contract.service.ContractTransactionProjectionService;
import com.susukkang.fgc.schedule.service.ScheduleService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/** 계약 상세 기본정보·탭 조회·수동 재검증을 제공하는 API. */
@Tag(name = "계약", description = "계약 상세 통합조회 및 재검증 API")
@RestController
@RequestMapping("/api/v1/contracts")
@RequiredArgsConstructor
public class ContractDetailController {

    private final ContractService contractService;
    private final ScheduleService scheduleService;
    private final CapCheckService capCheckService;
    private final ArbitrageService arbitrageService;
    private final ContractJournalProjectionService contractJournalProjectionService;
    private final ContractTransactionProjectionService contractTransactionProjectionService;

    @GetMapping("/{id}")
    public ApiResponse<ContractDetailResponse> getContractById(@PathVariable Long id) {
        return ApiResponse.success(contractService.selectContractDetailById(id));
    }

    @GetMapping("/{id}/status-events")
    public ApiResponse<List<ContractStatusEventResponse>> getContractStatusEvents(@PathVariable Long id) {
        return ApiResponse.success(contractService.selectStatusEventsByContractId(id));
    }

    @GetMapping("/{id}/schedules")
    public ApiResponse<ContractScheduleResponse> getContractSchedules(
            @PathVariable Long id,
            @RequestParam PaymentStage paymentStage) {
        return ApiResponse.success(scheduleService.selectByContractId(id, paymentStage));
    }

    @GetMapping("/{id}/cap-checks")
    public ApiResponse<List<CapCheckSaveResult>> getContractCapChecks(@PathVariable Long id) {
        return ApiResponse.success(Arrays.stream(PaymentStage.values())
                .map(stage -> capCheckService.findLatest(id, stage))
                .flatMap(java.util.Optional::stream)
                .toList());
    }

    @PostMapping("/{id}/cap-check")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<List<CapCheckSaveResult>> recheckCap(@PathVariable Long id) {
        return ApiResponse.success(contractService.recheckCap(id));
    }

    @PostMapping("/{id}/schedules/regenerate")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<List<Long>> regenerateSchedules(
            @PathVariable Long id,
            @RequestParam(name = "reason") String reason) {
        return ApiResponse.success(contractService.regenerateSchedules(id, reason));
    }

    @PostMapping("/{id}/arbitrage-check")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<ReArbitrageCheckResponse> reArbitrageCheck(
            @PathVariable Long id,
            @Valid @RequestBody ReArbitrageCheckRequest request,
            @AuthenticationPrincipal FgcUserDetails principal) {
        return ApiResponse.success(
                arbitrageService.reArbitrageCheck(id, request, principal.getUserId()));
    }

    @GetMapping("/{id}/arbitrage-checks")
    public ApiResponse<List<ArbitrageCheckView>> getArbitrageChecks(@PathVariable Long id) {
        return ApiResponse.success(arbitrageService.selectByContractId(id, PaymentStage.GA_TO_FC));
    }

    @GetMapping("/{id}/journals")
    public ApiResponse<List<ContractJournalResponse>> getContractJournals(@PathVariable Long id) {
        return ApiResponse.success(contractJournalProjectionService.findJournalsByContractId(id));
    }

    @GetMapping("/{id}/transactions")
    public ApiResponse<ContractTransactionTabResponse> getContractTransactions(@PathVariable Long id) {
        return ApiResponse.success(contractTransactionProjectionService.findTransactionsByContractId(id));
    }
}
