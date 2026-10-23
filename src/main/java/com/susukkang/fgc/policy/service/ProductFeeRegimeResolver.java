package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.policy.code.FeeRegimeReasonCode;
import com.susukkang.fgc.policy.code.FeeRegimeResolutionStatus;
import com.susukkang.fgc.policy.dto.FeeRegimeDecisionInput;
import com.susukkang.fgc.policy.dto.FeeRegimeResolution;
import com.susukkang.fgc.policy.repository.FeeRegimeResolverRepository;
import com.susukkang.fgc.policy.repository.FeeRegimeResolverRepository.RulesetCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 설명 : 계약의 분급 체계와 룰셋을 고르는 Resolver (FUN-014-03, #536). 읽기 전용이다.
 * 판정표는 {@link FeeRegimeDecisionTable}이 정하고, 이 클래스는 입력·후보 조회와 룰셋 후보 정리만 한다.
 * 규정만으로 정할 수 없는 경우는 임의 기본값 없이 REVIEW_REQUIRED 와 사유를 돌려준다.
 * 1,200%·차익거래 적용은 계약일 기준으로 따로 판정하므로 이 Resolver의 범위가 아니다(REG-19).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, noRollbackFor = FgcBusinessException.class)
public class ProductFeeRegimeResolver {

    private static final List<String> REG_REFS = List.of("REG-19");

    private final FeeRegimeDecisionInputService inputService;
    private final FeeRegimeResolverRepository resolverRepository;

    public FeeRegimeResolution resolve(Long contractId) {
        FeeRegimeDecisionInput input = inputService.getByContractId(contractId);
        List<Long> coveringOfferingIds = resolverRepository.findOfferingIdsCoveringContractDate(contractId);

        FeeRegimeDecisionTable.Decision decision = FeeRegimeDecisionTable.decide(input, coveringOfferingIds);
        return switch (decision.outcome()) {
            case NOT_APPLICABLE -> build(input, FeeRegimeResolutionStatus.NOT_APPLICABLE, decision.reasonCode(),
                    null, null, List.of(), List.of());
            case REVIEW_REQUIRED -> build(input, FeeRegimeResolutionStatus.REVIEW_REQUIRED, decision.reasonCode(),
                    null, null,
                    decision.reasonCode() == FeeRegimeReasonCode.OFFERING_NOT_UNIQUE ? coveringOfferingIds : List.of(),
                    List.of());
            case LOOKUP_RULESET -> selectRuleset(input);
        };
    }

    private FeeRegimeResolution selectRuleset(FeeRegimeDecisionInput input) {
        String policyType = FeeRegimeDecisionTable.FOUR_YEAR_2027.equals(input.declaredFeeRegimeCode())
                ? "FOUR_YEAR_COMMISSION" : "SEVEN_YEAR_COMMISSION";
        List<RulesetCandidate> candidates = resolverRepository.findRulesetCandidates(
                policyType, input.declaredFeeRegimeCode(), input.contractDate(), input.salesStartDate(),
                input.basicDocumentVersion(), input.basicDocumentDate());

        // 기초서류 버전이 지정된 룰셋이 있으면 모든 버전용(NULL) 룰셋보다 우선한다(#534 선택 키 규칙)
        if (candidates.stream().anyMatch(c -> c.getBasicDocumentVersion() != null)) {
            candidates = candidates.stream().filter(c -> c.getBasicDocumentVersion() != null).toList();
        }
        List<Long> candidateIds = candidates.stream().map(RulesetCandidate::getRulesetId).toList();

        if (candidates.isEmpty()) {
            return build(input, FeeRegimeResolutionStatus.REVIEW_REQUIRED, FeeRegimeReasonCode.RULESET_MISSING,
                    null, null, List.of(), List.of());
        }
        // LIMIT 1 로 하나를 고르지 않는다
        if (candidates.size() > 1) {
            return build(input, FeeRegimeResolutionStatus.REVIEW_REQUIRED, FeeRegimeReasonCode.RULESET_AMBIGUOUS,
                    null, null, List.of(), candidateIds);
        }
        RulesetCandidate only = candidates.getFirst();
        if (Boolean.TRUE.equals(only.getReviewRequired())) {
            return build(input, FeeRegimeResolutionStatus.REVIEW_REQUIRED, FeeRegimeReasonCode.RULESET_FLAGGED,
                    null, null, List.of(), candidateIds);
        }
        return build(input, FeeRegimeResolutionStatus.SELECTED, FeeRegimeReasonCode.RULESET_SELECTED,
                only.getRulesetId(), only.getPolicyVersionId(), List.of(), List.of());
    }

    private FeeRegimeResolution build(FeeRegimeDecisionInput input, FeeRegimeResolutionStatus status,
                                      FeeRegimeReasonCode reasonCode, Long rulesetId, Long policyVersionId,
                                      List<Long> candidateOfferingIds, List<Long> candidateRulesetIds) {
        return new FeeRegimeResolution(status, reasonCode, input.declaredFeeRegimeCode(), rulesetId, policyVersionId,
                input.contractDate(), input.salesStartDate(), input.basicDocumentVersion(), input.basicDocumentDate(),
                candidateOfferingIds, candidateRulesetIds, REG_REFS);
    }
}
