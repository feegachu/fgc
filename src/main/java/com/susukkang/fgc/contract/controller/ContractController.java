package com.susukkang.fgc.contract.controller;

import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.contract.dto.*;
import com.susukkang.fgc.contract.service.ContractService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    /**
     * 설명 : 검색 조건에 따라 보험계약 목록을 조회한다.
     *
     * @param condition 보험계약 검색 조건
     * @return 검색 조건에 해당하는 보험계약 목록
     * @author hjKang
     * @since 2026-08-05
     */
    @GetMapping
    // TODO: 계약 목록에 page, size 및 전체 건수 메타데이터를 적용한다.
    public ApiResponse<List<ContractView>> getContractListByCondition(@ModelAttribute ContractSearchCondition condition) {
        return ApiResponse.success(contractService.selectByCondition(condition));
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
    // TODO: 계약 생성 권한을 SETTLEMENT 역할로 제한한다.
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
    // TODO: 계약 수정 권한을 SETTLEMENT 역할로 제한한다.
    public ApiResponse<ContractResponse> updateContract(@PathVariable Long id, @Valid @RequestBody ContractUpdateRequest request) {
        return ApiResponse.success(contractService.updateContract(id, request));
    }
    /**
     * 설명 : 계약 상세보기 - 현재는 기본 계약정보를 반환한다.
     *       계약상태 사건 이력은 FUN-026 2차에서 포함한다.
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
}
