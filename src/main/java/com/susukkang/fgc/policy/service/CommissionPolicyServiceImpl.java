package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.policy.dto.ResolvedCommissionPolicy;
import org.springframework.stereotype.Service;

/**
 * 설명 : 현행 수수료 정책 조회 서비스의 임시 구현체
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-10
 */
@Service
public class CommissionPolicyServiceImpl implements CommissionPolicyService {

    /**
     * 설명 : 계약과 지급 단계에 적용할 현행 수수료 정책을 조회한다.
     *
     * @param contractId 계약 ID
     * @param paymentStage 지급 단계
     * @return 적용된 정책 버전과 수수료 규칙 목록
     */
    @Override
    public ResolvedCommissionPolicy resolveCurrentCommission(
            Long contractId,
            PaymentStage paymentStage
    ) {
        // TODO(FUN-011): policy_version과 commission_rule을 조회한다.
        throw new UnsupportedOperationException(
                "FUN-011 현행 수수료 정책 조회 기능이 아직 구현되지 않았습니다."
        );
    }
}
