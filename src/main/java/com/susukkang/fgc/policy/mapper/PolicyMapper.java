package com.susukkang.fgc.policy.mapper;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.policy.dto.ResolvedCommissionPolicy;
import com.susukkang.fgc.policy.dto.ResolvedCommissionRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 설명 : 계약과 지급 단계에 적용되는 현행 수수료 정책 및 규칙을 조회하는 Mapper
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-10
 */
@Mapper
public interface PolicyMapper {


    /**
     * 설명 : 계약과 지급 단계에 적용 가능한 현행 수수료 정책 목록을 조회한다.
     * 정책 중복 여부는 서비스 계층에서 조회 건수를 기준으로 판정한다.
     *
     * @param contractId 계약 ID
     * @param paymentStage 지급 단계
     * @return 적용 가능한 현행 수수료 정책 목록
     */
    List<ResolvedCommissionPolicy> findApplicableCurrentCommissionPolicies(
            @Param("contractId") Long contractId,
            @Param("paymentStage") PaymentStage paymentStage
    );

    /**
     * 설명 : 정책 버전, 계약 및 지급 단계를 기준으로 적용 가능한 수수료 규칙 목록을 조회한다.
     *
     * @param policyVersionId 정책 버전 ID
     * @param contractId 계약 ID
     * @param paymentStage 지급 단계
     * @return 적용 가능한 수수료 규칙 목록
     */
    List<ResolvedCommissionRule> findApplicableCommissionRules(
            @Param("policyVersionId") Long policyVersionId,
            @Param("contractId") Long contractId,
            @Param("paymentStage") PaymentStage paymentStage
    );
}