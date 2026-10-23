package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.policy.dto.FeeRegimeRulesetSelection;
import com.susukkang.fgc.policy.dto.FeeRegimeRulesetSelection.Ruleset;
import com.susukkang.fgc.policy.dto.FeeRegimeRulesetSelection.RulesetLimit;
import com.susukkang.fgc.policy.dto.FeeRegimeResolution;
import com.susukkang.fgc.policy.repository.FeeRegimeResolverRepository;
import com.susukkang.fgc.policy.repository.FeeRegimeResolverRepository.RulesetHeader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 설명 : 스케줄 생성·한도 검증이 부르는 분급 룰셋 단일 조회 서비스 (FUN-014-04, #537). 읽기 전용이다.
 * 판정은 {@link ProductFeeRegimeResolver}(#536)에 맡기고, SELECTED 이면 룰셋 헤더와 한도 행을 함께 돌려준다.
 * REVIEW_REQUIRED·NOT_APPLICABLE 은 예외로 바꾸지 않고 사유와 사용한 키 값을 그대로 돌려준다.
 * NOT_APPLICABLE 이면 호출부(#649)는 기존 findApplicableCurrentCommissionPolicies 경로를 그대로 써야 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, noRollbackFor = FgcBusinessException.class)
public class FeeRegimeRulesetService {

    private final ProductFeeRegimeResolver resolver;
    private final FeeRegimeResolverRepository resolverRepository;

    public FeeRegimeRulesetSelection resolve(Long contractId) {
        FeeRegimeResolution resolution = resolver.resolve(contractId);
        if (resolution.rulesetId() == null) {
            return new FeeRegimeRulesetSelection(resolution, null, List.of());
        }

        // 승인된 정책 아래의 룰셋은 잠금 트리거로 바뀌지 않으므로, 같은 룰셋 ID는 다시 읽어도 같은 값이다
        RulesetHeader header = resolverRepository.findRulesetHeader(resolution.rulesetId())
                .orElseThrow(() -> new IllegalStateException(
                        "선택된 룰셋을 읽을 수 없습니다: rulesetId=" + resolution.rulesetId()));
        List<RulesetLimit> limits = resolverRepository.findRulesetLimits(resolution.rulesetId()).stream()
                .map(row -> new RulesetLimit(row.getLimitCode(), row.getLimitPct(),
                        Boolean.TRUE.equals(row.getIncludesIndirectExpense())))
                .toList();

        return new FeeRegimeRulesetSelection(resolution, toRuleset(header), limits);
    }

    private static Ruleset toRuleset(RulesetHeader h) {
        return new Ruleset(h.getRulesetId(), h.getPolicyVersionId(), h.getFeeRegimeCode(),
                h.getSalesStartDateFrom(), h.getSalesStartDateTo(), h.getBasicDocumentVersion(),
                h.getBasicDocumentDateFrom(), h.getBasicDocumentDateTo(), h.getMaxInstallments(),
                h.getLongTermMaintenanceFrom(), h.getLongTermMaintenanceTo());
    }
}
