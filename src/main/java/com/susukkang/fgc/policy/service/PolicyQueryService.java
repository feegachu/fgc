package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.code.PolicyStatus;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.dto.CapRuleSetResponse;
import com.susukkang.fgc.policy.dto.CapRuleSetRow;
import com.susukkang.fgc.policy.dto.CommissionRuleResponse;
import com.susukkang.fgc.policy.dto.PolicyDetailResponse;
import com.susukkang.fgc.policy.dto.PolicyDraft;
import com.susukkang.fgc.policy.dto.PolicyReferenceResponse;
import com.susukkang.fgc.policy.dto.PolicyVersionResponse;
import com.susukkang.fgc.policy.dto.PolicyVersionRow;
import com.susukkang.fgc.policy.dto.RefundRateTableResponse;
import com.susukkang.fgc.policy.dto.RefundRateTableRow;
import com.susukkang.fgc.policy.entity.PolicyVersion;
import com.susukkang.fgc.policy.repository.CapRuleSetRepository;
import com.susukkang.fgc.policy.repository.CommissionRuleRepository;
import com.susukkang.fgc.policy.repository.PolicyVersionRepository;
import com.susukkang.fgc.policy.repository.PolicyWriteRepository;
import com.susukkang.fgc.policy.repository.RefundRateTableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 설명 : POL-W01 정책·룰셋 조회 전용 서비스 (FGC-FUN-012·013, IF-API-09·10).
 * 계산 엔진(CommissionPolicyService)과 분리된 화면·API 조회 경로다.
 * 정책 작성·승인은 PolicyWriteService에서 처리한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PolicyQueryService {

    private final PolicyVersionRepository policyVersionRepository;
    private final CommissionRuleRepository commissionRuleRepository;
    private final CapRuleSetRepository capRuleSetRepository;
    private final RefundRateTableRepository refundRateTableRepository;
    private final PolicyWriteRepository policyWriteRepository;
    private final PolicyWriteService policyWriteService;

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
        PolicyDraft draft = policyWriteRepository.readDraft(policy);
        return new PolicyDetailResponse(
                PolicyVersionResponse.from(header),
                header.getSourceRefs(),
                commissionRules,
                capRuleSets,
                refundRateTables,
                draft,
                policyWriteRepository.findApprovalActions(policyVersionId),
                policyWriteService.allowedActions(policy),
                policy.getApprovalEvidenceRef(),
                policyWriteRepository.applicationPeriod(policyVersionId),
                references(draft));
    }

    /** 근거 info 패널: 정책에 연결된 참조마다 레지스트리 항목명과 이 정책버전에 저장된 상세를 붙인다. */
    private List<PolicyReferenceResponse> references(PolicyDraft draft) {
        Map<String, PolicyDraft.SourceDetailDraft> details = draft.sourceDetails().stream()
                .collect(Collectors.toMap(PolicyDraft.SourceDetailDraft::ref, d -> d, (a, b) -> a));
        Set<String> codes = new LinkedHashSet<>();
        Stream.concat(draft.regulationRefs().stream(), draft.sourceRefs().stream())
                .map(PolicyWriteService::referenceCode).filter(Objects::nonNull).forEach(codes::add);
        Map<String, String> titles = policyWriteRepository.referenceTitles(codes);
        List<PolicyReferenceResponse> rows = new ArrayList<>();
        draft.regulationRefs().forEach(ref -> rows.add(reference(ref, "REGULATION", titles, details)));
        draft.sourceRefs().forEach(ref -> rows.add(reference(ref, "SOURCE", titles, details)));
        return rows;
    }

    private static PolicyReferenceResponse reference(String ref, String group, Map<String, String> titles,
                                                     Map<String, PolicyDraft.SourceDetailDraft> details) {
        String code = PolicyWriteService.referenceCode(ref);
        PolicyDraft.SourceDetailDraft detail = details.get(ref);
        return new PolicyReferenceResponse(ref, code, group, code == null ? null : titles.get(code),
                detail == null ? null : detail.title(), detail == null ? null : detail.explanation(),
                detail == null || detail.url() == null || detail.url().isBlank() ? null : detail.url());
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
