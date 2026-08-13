package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.contract.dto.ContractTransactionResponse;

import java.util.List;

/**
 * 계약 상세 화면(CONT-W02)의 지급 건 탭
 */
public interface ContractTransactionProjectionService {

    /**
     * contractId에 귀속된 모든 지급 건(DRAFT/CONFIRMED/CANCELLED 전부)을 지급 건 단위로 묶어 돌려준다
     * 귀속된 지급 건이 없으면 빈 리스트를 돌려준다
     */
    List<ContractTransactionResponse> findTransactionsByContractId(Long contractId);
}
