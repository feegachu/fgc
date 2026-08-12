package com.susukkang.fgc.policy.mapper;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.PolicyStatus;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.policy.dto.CapRuleSetRow;
import com.susukkang.fgc.policy.dto.CommissionRuleRow;
import com.susukkang.fgc.policy.dto.PolicyVersionRow;
import com.susukkang.fgc.policy.dto.RefundRateTableRow;
import com.susukkang.fgc.policy.dto.ResolvedCommissionPolicy;
import com.susukkang.fgc.policy.dto.ResolvedCommissionRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
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
            @Param("paymentStage") PaymentStage paymentStage);

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

    /**
     * 설명 : IF-API-09 — 정책 버전 목록을 조회한다 (FGC-FUN-012·013, POL-W01).
     * 세 필터는 전부 선택이며, asOf 는 해당 기준일에 유효한(effective 범위 포함) 버전만 남긴다.
     *
     * @param type 정책 유형 필터 (null 이면 전체)
     * @param asOf 기준일 필터 (null 이면 전체 — 화면정의서: 정책은 계약일 기준으로 고르는 경우가 많다)
     * @param status 정책 상태 필터 (null 이면 전체)
     * @return 정책 버전 목록 (정책코드, 버전 순)
     */
    List<PolicyVersionRow> selectPolicyVersions(
            @Param("type") PolicyType type,
            @Param("asOf") LocalDate asOf,
            @Param("status") PolicyStatus status);

    /**
     * 설명 : IF-API-10 — 정책 버전 상세 헤더 1건을 조회한다.
     *
     * @param policyVersionId 정책 버전 ID
     * @return 헤더 1건, 없으면 null
     */
    PolicyVersionRow selectPolicyVersionById(@Param("policyVersionId") Long policyVersionId);

    /**
     * 설명 : IF-API-10 — 정책 버전에 속한 수수료 규칙 목록을 조회한다 (POL-W01 수수료 규칙 탭).
     *
     * @param policyVersionId 정책 버전 ID
     * @return 수수료 규칙 목록 (지급단계, 회차 순)
     */
    List<CommissionRuleRow> selectCommissionRules(@Param("policyVersionId") Long policyVersionId);

    /**
     * 설명 : IF-API-10 — 정책 버전에 속한 1,200% 룰셋과 항목별 산입 판정을 조회한다
     * (POL-W01 1,200% 룰셋 탭). 정책버전당 룰셋이 복수일 수 있다(uq_cap_rule_set_scope).
     *
     * @param policyVersionId 정책 버전 ID
     * @return 룰셋 목록 (항목 판정 중첩)
     */
    List<CapRuleSetRow> selectCapRuleSets(@Param("policyVersionId") Long policyVersionId);

    /**
     * 설명 : IF-API-10 — 정책 버전에 속한 예상 해약환급률표와 차월 라인을 조회한다
     * (POL-W01 예상 해약환급률표 탭). 조인은 보험사·상품 기준이며 product_offering_id 는 쓰지 않는다.
     *
     * @param policyVersionId 정책 버전 ID
     * @return 환급률표 목록 (차월 라인 중첩, 1~36차월 순)
     */
    List<RefundRateTableRow> selectRefundRateTables(@Param("policyVersionId") Long policyVersionId);
}