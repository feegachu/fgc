package com.susukkang.fgc.contract.controller;

import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.contract.dto.*;
import com.susukkang.fgc.contract.service.ContractService;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.schedule.service.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("hasAnyRole('SETTLEMENT', 'SYSTEM_ADMIN')")
    public ApiResponse<ContractResponse> createContract(@Valid @RequestBody ContractCreateRequest request ) {
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
    @PreAuthorize("hasAnyRole('SETTLEMENT', 'SYSTEM_ADMIN')")
    public ApiResponse<ContractResponse> updateContract(@PathVariable Long id, @Valid @RequestBody ContractUpdateRequest request) {
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
    @GetMapping("/status-events")
    public ApiResponse<List<ContractStatusEventResponse>> getContractStatusEvents(
            @RequestParam Long insurerId,
            @RequestParam String contractNo
    ) {
        return ApiResponse.success(contractService.selectStatusEventsByBusinessKey(insurerId, contractNo));
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
}
