package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.policy.dto.FeeRegimeDecisionInput;

/**
 * 설명 : 분급 수수료체계 판정에 필요한 입력값을 계약 기준으로 조회하는 서비스
 */
public interface FeeRegimeDecisionInputService {

    /**
     * 계약 ID로 판정 입력을 조회한다. 값은 DB 원본 그대로이며 체계 판정은 하지 않는다.
     *
     * @param contractId 계약 ID
     * @return 판정 입력 (계약이 없으면 FgcBusinessException COMMON_004)
     */
    FeeRegimeDecisionInput getByContractId(Long contractId);
}
