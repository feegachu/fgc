package com.susukkang.fgc.contract.controller;

import com.susukkang.fgc.arbitrage.dto.ReArbitrageCheckRequest;
import com.susukkang.fgc.arbitrage.dto.ReArbitrageCheckResponse;
import com.susukkang.fgc.arbitrage.service.ArbitrageService;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.contract.dto.*;
import com.susukkang.fgc.contract.service.ContractService;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.schedule.service.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 설명 : ContractController
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
@RestController
@RequestMapping("/api/v1/contracts")
@RequiredArgsConstructor
public class ContractController {
    private final ContractService contractService;
    private final ScheduleService scheduleService;
    private final ArbitrageService arbitrageService;
    private final CapCheckService capCheckService;

    /**
     * 설명 : 검색 조건에 따라 보험계약 목록을 조회한다.
     *
     * @param condition 보험계약 검색 조건
     * @return 검색 조건에 해당하는 보험계약 목록
     * @author hjKang
     * @since 2026-08-05
     */
    @GetMapping
    public ApiResponse<PageResponse<ContractView>> getContractListByCondition(
            @ModelAttribute @Valid ContractSearchCondition condition,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(contractService.selectByCondition(condition, page, size));
    }

    /**
     * 설명 : 보험계약을 신규 등록한다.
     *
     * @param request 보험계약 등록 요청 정보
     * @return 등록된 보험계약 정보
     * @author hjKang
     * @since 2026-08-05
     */
    @PostMapping
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<ContractCreateResponse> createContract(@Valid @RequestBody ContractCreateRequest request ) {
        return ApiResponse.success(contractService.createContract(request));
    }
    /**
     * 설명 : 보험계약을 계약 수정
     *  계약 조회 -> 계약 상세보기 -> 계약 수정 -> 입력값 입력후 수정
     * @param id,request 수정할 ID + 수정할 정보
     * @return 계약 수정 응답 정보
     * @author hjKang
     * @since 2026-08-05
     */
    @PutMapping("/{id}")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<ContractUpdateResponse> updateContract(@PathVariable Long id, @Valid @RequestBody ContractUpdateRequest request) {
        return ApiResponse.success(contractService.updateContract(id, request));
    }
    /**
     * 설명 : 계약 상세보기 - 기본 계약정보를 반환한다.
     *
     * @param id 계약Id
     * @return 계약 상세보기 응답 정보
     * @author hjKang
     * @since 2026-08-05
     */
    @GetMapping("/{id}")
    public ApiResponse<ContractDetailResponse> getContractById(@PathVariable Long id) {
        return ApiResponse.success(contractService.selectContractDetailById(id));
    }

    /** IF-API-16 계약 상태 사건과 Job별 처리 이력을 조회한다. */
    @GetMapping("/{id}/status-events")
    public ApiResponse<List<ContractStatusEventResponse>> getContractStatusEvents(@PathVariable Long id) {
        return ApiResponse.success(contractService.selectStatusEventsByContractId(id));
    }
    /**
     * 계약 ID에 해당하는 운영용 예상 스케줄 헤더 목록을 조회한다.
     *
     * @param contractId 계약 ID
     * @return 계약에 연결된 운영용 예상 스케줄 헤더 목록
     */
    @GetMapping("/{contractId}/schedules")
    public ApiResponse<ContractScheduleResponse> getContractSchedules(
            @PathVariable Long contractId,
            @RequestParam PaymentStage paymentStage
    ) {
        return ApiResponse.success(
                scheduleService.selectByContractId(contractId, paymentStage)
        );
    }

    /** IF-API-14 계약 상세 탭용 지급단계별 최신 1,200% 판정 조회. */
    @GetMapping("/{id}/cap-checks")
    public ApiResponse<List<CapCheckSaveResult>> getContractCapChecks(@PathVariable Long id) {
        return ApiResponse.success(
                java.util.Arrays.stream(PaymentStage.values())
                        .map(stage -> capCheckService.findLatest(id, stage))
                        .flatMap(java.util.Optional::stream)
                        .toList()
        );
    }

    /** 계약 생성·수정 후 사용하는 동일한 실시간 한도 계산을 수동으로 다시 실행한다. */
    @PostMapping("/{id}/cap-check")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<List<CapCheckSaveResult>> recheckCap(@PathVariable Long id) {
        return ApiResponse.success(contractService.recheckCap(id));
    }
    /**
     * 설명 : 계약 ID를 기준으로 차익거래 수동 검증을 실행한다.
     * @param id 계약 ID
     * @param request 차익거래 검증 요청 정보
     * @return 차익거래 검증 결과
     * @author hjKang
     * @since 2026-08-12
     */
    @PostMapping("/{id}/arbitrage-check")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<ReArbitrageCheckResponse> reArbitrageCheck(
            @PathVariable Long id,
            @Valid @RequestBody ReArbitrageCheckRequest request,
            @AuthenticationPrincipal FgcUserDetails principal) {
        return ApiResponse.success(
                arbitrageService.reArbitrageCheck(id, request, principal.getUserId())
        );
    }

    /**
     * 설명 : 계약과 지급단계의 기준일별 차익거래 검증 결과를 조회한다.
     *
     * @param id 계약 ID
     * @return 기준일별 차익거래 검증 결과
     * @author hjKang
     * @since 2026-08-12
     */
    @GetMapping("/{id}/arbitrage-checks")
    public ApiResponse<List<com.susukkang.fgc.arbitrage.dto.ArbitrageCheckView>> getArbitrageChecks(
            @PathVariable Long id) {
        return ApiResponse.success(
                arbitrageService.selectByContractId(id, PaymentStage.GA_TO_FC)
        );
    }
}
