package com.susukkang.fgc.policy.dto;

import com.susukkang.fgc.policy.code.FeeRegimeReasonCode;
import com.susukkang.fgc.policy.code.FeeRegimeResolutionStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 분급 체계 판정 결과. 판정에 쓴 키 값과 근거를 함께 남겨 같은 입력이면 같은 결과를 재현할 수 있게 한다.
 *
 * @param status                  판정 상태
 * @param reasonCode              사유 코드
 * @param declaredFeeRegimeCode   판매버전이 선언한 체계 코드
 * @param rulesetId               선택된 룰셋 ID (SELECTED 일 때만)
 * @param policyVersionId         선택된 룰셋의 정책버전 ID (SELECTED 일 때만)
 * @param contractDate            판정에 쓴 계약일
 * @param salesStartDate          판정에 쓴 판매개시일
 * @param basicDocumentVersion    판정에 쓴 기초서류 버전
 * @param basicDocumentDate       판정에 쓴 기초서류 작성/변경일
 * @param candidateOfferingIds    OFFERING_NOT_UNIQUE 일 때 계약일을 포함하는 판매버전 ID
 * @param candidateRulesetIds     RULESET_AMBIGUOUS·RULESET_FLAGGED 일 때 후보 룰셋 ID
 * @param regRefs                 근거 REG
 */
public record FeeRegimeResolution(
        FeeRegimeResolutionStatus status,
        FeeRegimeReasonCode reasonCode,
        String declaredFeeRegimeCode,
        Long rulesetId,
        Long policyVersionId,
        LocalDate contractDate,
        LocalDate salesStartDate,
        String basicDocumentVersion,
        LocalDate basicDocumentDate,
        List<Long> candidateOfferingIds,
        List<Long> candidateRulesetIds,
        List<String> regRefs
) {
}
