package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.code.PolicyStatus;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.dto.CapRuleSetResponse;
import com.susukkang.fgc.policy.dto.CommissionRuleResponse;
import com.susukkang.fgc.policy.dto.PolicyDetailResponse;
import com.susukkang.fgc.policy.dto.PolicyVersionResponse;
import com.susukkang.fgc.policy.dto.PolicyVersionRow;
import com.susukkang.fgc.policy.dto.RefundRateTableResponse;
import com.susukkang.fgc.policy.mapper.PolicyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 설명 : POL-W01 정책·룰셋 조회 전용 서비스 (FGC-FUN-012·013, IF-API-09·10).
 * 계산 엔진(CommissionPolicyService)과 분리된 화면·API 조회 경로다.
 * 1차는 조회만 한다 — 정책 등록·승인은 2차(POL-W02).
 */
@Service
@Transactional(readOnly = true)
public class PolicyQueryService {

    private final PolicyMapper policyMapper;

    public PolicyQueryService(PolicyMapper policyMapper) {
        this.policyMapper = policyMapper;
    }

    /** IF-API-09 — 정책 버전 목록. 필터 3개는 전부 선택이며 0건은 빈 목록이다(404 아님). */
    public List<PolicyVersionResponse> findPolicyVersions(PolicyType type, LocalDate asOf, PolicyStatus status) {
        return policyMapper.selectPolicyVersions(type, asOf, status).stream()
                .map(PolicyVersionResponse::from)
                .toList();
    }

    /** IF-API-10 — 정책 버전 상세(POL-W01 탭별 데이터). 없는 ID 는 FGC-COMMON-004(404). */
    public PolicyDetailResponse findPolicyDetail(Long policyVersionId) {
        PolicyVersionRow header = policyMapper.selectPolicyVersionById(policyVersionId);
        if (header == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", policyVersionId));
        }
        List<CommissionRuleResponse> commissionRules =
                policyMapper.selectCommissionRules(policyVersionId).stream()
                        .map(CommissionRuleResponse::from)
                        .toList();
        List<CapRuleSetResponse> capRuleSets =
                policyMapper.selectCapRuleSets(policyVersionId).stream()
                        .map(CapRuleSetResponse::from)
                        .toList();
        List<RefundRateTableResponse> refundRateTables =
                policyMapper.selectRefundRateTables(policyVersionId).stream()
                        .map(RefundRateTableResponse::from)
                        .toList();
        return new PolicyDetailResponse(
                PolicyVersionResponse.from(header),
                header.getSourceRefs(),
                commissionRules,
                capRuleSets,
                refundRateTables);
    }
}
