package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.policy.code.FeeRegimeReasonCode;
import com.susukkang.fgc.policy.code.FeeRegimeResolutionStatus;
import com.susukkang.fgc.policy.dto.FeeRegimeRulesetSelection;
import com.susukkang.fgc.policy.dto.FeeRegimeRulesetSelection.Ruleset;
import com.susukkang.fgc.policy.dto.FeeRegimeRulesetSelection.RulesetLimit;
import com.susukkang.fgc.policy.dto.FeeRegimeResolution;
import com.susukkang.fgc.policy.repository.FeeRegimeResolverRepository;
import com.susukkang.fgc.policy.repository.FeeRegimeResolverRepository.RulesetHeader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 설명 : 스케줄 생성·한도 검증이 부르는 분급 룰셋 단일 조회 서비스 (FUN-014-04, #537). 읽기 전용이다.
 * 판정은 {@link ProductFeeRegimeResolver}(#536)에 맡기고, SELECTED 이면 룰셋 헤더와 한도 행을 함께 돌려준다.
 * REVIEW_REQUIRED·NOT_APPLICABLE 은 예외로 바꾸지 않고 사유와 사용한 키 값을 그대로 돌려준다.
 * NOT_APPLICABLE 이면 호출부(#649)는 기존 findApplicableCurrentCommissionPolicies 경로를 그대로 써야 한다.
 * 선택된 룰셋에 필수 한도 행(UPFRONT_TOTAL·MAINTENANCE_MONTHLY, 장기유지 구간이 있으면 LONG_TERM_MONTHLY)이 없으면
 * DB 제약으로 막을 수 없으므로 REVIEW_REQUIRED(RULESET_LIMIT_MISSING)로 돌려준다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, noRollbackFor = FgcBusinessException.class)
public class FeeRegimeRulesetService {

    private static final String UPFRONT_TOTAL = "UPFRONT_TOTAL";
    private static final String MAINTENANCE_MONTHLY = "MAINTENANCE_MONTHLY";
    private static final String LONG_TERM_MONTHLY = "LONG_TERM_MONTHLY";

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

        List<String> missing = missingRequiredLimits(header, limits);
        if (!missing.isEmpty()) {
            // 한도 행이 비면 호출부가 "한도 없음"으로 읽어 REG-02·03·05 상한 검증 없이 확정할 수 있다 → 자동 확정하지 않는다
            return new FeeRegimeRulesetSelection(limitMissing(resolution), null, List.of());
        }

        return new FeeRegimeRulesetSelection(resolution, toRuleset(header), limits);
    }

    // 필수 한도: 모든 체계에 선지급 총액·유지관리 월액, 장기유지 구간이 있는 룰셋(7년)에는 장기유지 월액
    private static List<String> missingRequiredLimits(RulesetHeader header, List<RulesetLimit> limits) {
        List<String> required = new ArrayList<>(List.of(UPFRONT_TOTAL, MAINTENANCE_MONTHLY));
        if (header.getLongTermMaintenanceFrom() != null) {
            required.add(LONG_TERM_MONTHLY);
        }
        return required.stream()
                .filter(code -> limits.stream().noneMatch(l -> l.limitCode().equals(code)))
                .toList();
    }

    private static FeeRegimeResolution limitMissing(FeeRegimeResolution r) {
        return new FeeRegimeResolution(FeeRegimeResolutionStatus.REVIEW_REQUIRED,
                FeeRegimeReasonCode.RULESET_LIMIT_MISSING, r.declaredFeeRegimeCode(), r.rulesetId(),
                r.policyVersionId(), r.contractDate(), r.salesStartDate(), r.basicDocumentVersion(),
                r.basicDocumentDate(), r.candidateOfferingIds(), List.of(r.rulesetId()), r.regRefs());
    }

    private static Ruleset toRuleset(RulesetHeader h) {
        return new Ruleset(h.getRulesetId(), h.getPolicyVersionId(), h.getFeeRegimeCode(),
                h.getSalesStartDateFrom(), h.getSalesStartDateTo(), h.getBasicDocumentVersion(),
                h.getBasicDocumentDateFrom(), h.getBasicDocumentDateTo(), h.getMaxInstallments(),
                h.getLongTermMaintenanceFrom(), h.getLongTermMaintenanceTo());
    }
}
