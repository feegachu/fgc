package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.code.FeeComponentType;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.policy.code.FeeRegimeReasonCode;
import com.susukkang.fgc.policy.code.FeeRegimeResolutionStatus;
import com.susukkang.fgc.policy.dto.FeeRegimeResolution;
import com.susukkang.fgc.policy.dto.FeeRegimeRulesetSelection;
import com.susukkang.fgc.policy.dto.FeeRegimeSchedulePolicy;
import com.susukkang.fgc.policy.dto.ResolvedCommissionPolicy;
import com.susukkang.fgc.policy.dto.ResolvedCommissionRule;
import com.susukkang.fgc.policy.repository.PolicyVersionRepository;
import com.susukkang.fgc.schedule.code.ScheduleRegime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** 등록·승인한 분급 정책을 후속 예정 스케줄 생성기가 그대로 사용할 수 있도록 조회한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, noRollbackFor = FgcBusinessException.class)
public class FeeRegimeSchedulePolicyService {
    private final FeeRegimeRulesetService rulesetService;
    private final CommissionPolicyService commissionPolicyService;
    private final PolicyVersionRepository policyVersionRepository;

    public FeeRegimeSchedulePolicy resolve(Long contractId, PaymentStage paymentStage) {
        FeeRegimeRulesetSelection selection = rulesetService.resolveForSchedule(contractId, paymentStage);
        if (!selection.isSelected()) return new FeeRegimeSchedulePolicy(selection, null, null, List.of());
        var ruleset = selection.ruleset();
        var policy = policyVersionRepository.findById(ruleset.policyVersionId()).orElseThrow();
        List<ResolvedCommissionRule> rules;
        try {
            rules = commissionPolicyService.resolveCommissionRules(policy.getPolicyVersionId(), contractId, paymentStage);
        } catch (FgcBusinessException e) {
            Object reason = e.getParams().get("reason");
            if (reason == null || !Set.of("RULE_MISSING", "RULE_DUPLICATE", "INVALID_RULE").contains(reason)) throw e;
            return invalidRules(selection);
        }
        if (rules.stream().anyMatch(rule -> !fits(rule, ruleset))) return invalidRules(selection);
        List<String> refs = Stream.of(policy.getRegulationRefs(), policy.getSourceRefs(), ruleset.sourceRefs())
                .flatMap(List::stream).distinct().toList();
        ResolvedCommissionPolicy commission = ResolvedCommissionPolicy.builder()
                .policyVersionId(policy.getPolicyVersionId()).policyType(policy.getPolicyType())
                .paymentStage(paymentStage).scheduleRegime(ScheduleRegime.valueOf(ruleset.feeRegimeCode()))
                .rules(rules).build();
        return new FeeRegimeSchedulePolicy(selection, commission, policy.getVersionNo(), refs);
    }

    private static boolean fits(ResolvedCommissionRule rule, FeeRegimeRulesetSelection.Ruleset ruleset) {
        if (rule.getInstallmentTo() > ruleset.maxInstallments()) return false;
        if (rule.getFeeComponentType() != FeeComponentType.LONG_TERM_MAINTENANCE) return true;
        return "SEVEN_YEAR_2029".equals(ruleset.feeRegimeCode())
                && ruleset.longTermMaintenanceFrom() != null
                && rule.getInstallmentFrom() >= ruleset.longTermMaintenanceFrom()
                && rule.getInstallmentTo() <= ruleset.longTermMaintenanceTo();
    }

    private static FeeRegimeSchedulePolicy invalidRules(FeeRegimeRulesetSelection selection) {
        FeeRegimeResolution r = selection.resolution();
        FeeRegimeResolution review = new FeeRegimeResolution(FeeRegimeResolutionStatus.REVIEW_REQUIRED,
                FeeRegimeReasonCode.COMMISSION_RULE_INVALID, r.declaredFeeRegimeCode(), null, null,
                r.contractDate(), r.salesStartDate(), r.basicDocumentVersion(), r.basicDocumentDate(),
                r.candidateOfferingIds(), List.of(r.rulesetId()), r.regRefs());
        return new FeeRegimeSchedulePolicy(new FeeRegimeRulesetSelection(review, null, List.of()),
                null, null, List.of());
    }
}
