package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.code.PolicyStatus;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.dto.CapRuleSetResponse;
import com.susukkang.fgc.policy.dto.CapRuleSetRow;
import com.susukkang.fgc.policy.dto.CommissionRuleResponse;
import com.susukkang.fgc.policy.dto.PolicyDetailResponse;
import com.susukkang.fgc.policy.dto.PolicyVersionResponse;
import com.susukkang.fgc.policy.dto.PolicyVersionRow;
import com.susukkang.fgc.policy.dto.RefundRateTableResponse;
import com.susukkang.fgc.policy.dto.RefundRateTableRow;
import com.susukkang.fgc.policy.entity.PolicyVersion;
import com.susukkang.fgc.policy.repository.CapRuleSetRepository;
import com.susukkang.fgc.policy.repository.CommissionRuleRepository;
import com.susukkang.fgc.policy.repository.PolicyVersionRepository;
import com.susukkang.fgc.policy.repository.RefundRateTableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 설명 : POL-W01 정책·룰셋 조회 전용 서비스 (FGC-FUN-012·013, IF-API-09·10).
 * 계산 엔진(CommissionPolicyService)과 분리된 화면·API 조회 경로다.
 * 1차는 조회만 한다 — 정책 등록·승인은 2차(POL-W02).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PolicyQueryService {

    private final PolicyVersionRepository policyVersionRepository;
    private final CommissionRuleRepository commissionRuleRepository;
    private final CapRuleSetRepository capRuleSetRepository;
    private final RefundRateTableRepository refundRateTableRepository;

    /** IF-API-09 — 정책 버전 목록. 필터 3개는 전부 선택이며 0건은 빈 목록이다(404 아님). */
    public List<PolicyVersionResponse> findPolicyVersions(PolicyType type, LocalDate asOf, PolicyStatus status) {
        List<PolicyVersion> policies = policyVersionRepository.selectPolicyVersions(type, asOf, status);
        Map<Long, String> userLogins = findUserLogins(policies);
        return policies.stream()
                .map(policy -> toRow(policy, userLogins))
                .map(PolicyVersionResponse::from)
                .toList();
    }

    /** IF-API-10 — 정책 버전 상세(POL-W01 탭별 데이터). 없는 ID 는 FGC-COMMON-004(404). */
    public PolicyDetailResponse findPolicyDetail(Long policyVersionId) {
        PolicyVersion policy = policyVersionRepository.findById(policyVersionId)
                .orElseThrow(() -> new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", policyVersionId)));
        PolicyVersionRow header = toRow(policy, findUserLogins(List.of(policy)));
        List<CommissionRuleResponse> commissionRules =
                commissionRuleRepository.selectCommissionRules(policyVersionId).stream()
                        .map(CommissionRuleResponse::from)
                        .toList();
        List<CapRuleSetResponse> capRuleSets =
                CapRuleSetRow.fromDetails(capRuleSetRepository.selectCapRuleSets(policyVersionId)).stream()
                        .map(CapRuleSetResponse::from)
                        .toList();
        List<RefundRateTableResponse> refundRateTables =
                RefundRateTableRow.fromDetails(refundRateTableRepository.selectRefundRateTables(policyVersionId)).stream()
                        .map(RefundRateTableResponse::from)
                        .toList();
        return new PolicyDetailResponse(
                PolicyVersionResponse.from(header),
                header.getSourceRefs(),
                commissionRules,
                capRuleSets,
                refundRateTables);
    }

    private Map<Long, String> findUserLogins(List<PolicyVersion> policies) {
        List<Long> userIds = policies.stream()
                .flatMap(policy -> Stream.of(policy.getCreatedBy(), policy.getApprovedBy()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return policyVersionRepository.findUserLogins(userIds).stream()
                .collect(Collectors.toMap(PolicyVersionRepository.UserLogin::getUserId,
                        PolicyVersionRepository.UserLogin::getLoginId));
    }

    private PolicyVersionRow toRow(PolicyVersion policy, Map<Long, String> userLogins) {
        return PolicyVersionRow.from(policy,
                policy.getCreatedBy() == null ? null : userLogins.get(policy.getCreatedBy()),
                policy.getApprovedBy() == null ? null : userLogins.get(policy.getApprovedBy()));
    }
}
