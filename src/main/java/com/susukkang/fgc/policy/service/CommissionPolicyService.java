package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.policy.dto.ResolvedCommissionPolicy;

import java.time.LocalDate;

/**
 * 설명 : 계약과 지급 단계에 적용할 현행 수수료 정책을 조회하는 서비스
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-10
 */
public interface CommissionPolicyService {

    /**
     * 설명 : 계약 ID와 지급 단계를 기준으로 적용할 현행 수수료 정책과 규칙 목록을 조회한다.
     *
     * @param contractId 계약 ID
     * @param paymentStage 지급 단계(원수사→GA, GA→설계사)
     * @return 적용된 정책 버전과 수수료 규칙 목록
     */
    ResolvedCommissionPolicy resolveCurrentCommission(
            Long contractId,
            PaymentStage paymentStage
    );

    /** 지정 기준일에 유효한 회사 공통 안분정책 버전을 조회한다. */
    Long resolveCurrentAllocationPolicyVersion(LocalDate asOf);
}
