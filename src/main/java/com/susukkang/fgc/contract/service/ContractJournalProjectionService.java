package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.contract.dto.ContractJournalResponse;

import java.util.List;

/**
 * 계약 상세 화면(CONT-W02)의 검증원장 탭
 */
public interface ContractJournalProjectionService {

    /**
     * contractId에 연결된 모든 분개(DRAFT/POSTED/REVERSED 전부)를 헤더 단위로 묶어 돌려준다.
     * 연결된 분개가 없으면 빈 리스트를 돌려준다
     */
    List<ContractJournalResponse> findJournalsByContractId(Long contractId);
}
