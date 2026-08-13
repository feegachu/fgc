package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.contract.dto.ContractTransactionTabResponse;

/**
 * 계약 상세 화면(CONT-W02)의 지급·대사 탭(탭6). 지급 건(commission_transaction+
 * transaction_attribution)과 대사 결과(reconciliation_result)를 함께 돌려준다
 */
public interface ContractTransactionProjectionService {

    /**
     * contractId 기준 지급 건 목록(DRAFT/CONFIRMED/CANCELLED 전부, 귀속행 없으면 빈 리스트)과
     * 대사 결과 목록(없으면 빈 리스트)을 함께 조회한다.
     */
    ContractTransactionTabResponse findTransactionsByContractId(Long contractId);
}
