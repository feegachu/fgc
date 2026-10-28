package com.susukkang.fgc.policy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.dto.PolicyDraft;
import com.susukkang.fgc.policy.dto.PolicyDraft.CommissionRuleDraft;
import com.susukkang.fgc.policy.dto.PolicyDraft.FeeRegimeRuleDraft;
import com.susukkang.fgc.policy.dto.PolicyFeeLimit;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.LongStream;

/** FUN-017·제49조: ACTIVE 교체본은 이전 버전의 유효 기간과 상세 선택 범위를 모두 포함한다. */
@Component
public class PolicySuccessionValidator {
    public void validate(PolicyDraft previous, PolicyDraft replacement) {
        validateHeader(previous, replacement);
        validateFinancialTerms(previous, replacement);
        for (CommissionRuleDraft oldRule : previous.commissionRules()) {
            List<long[]> intervals = replacement.commissionRules().stream()
                    .filter(rule -> sameCommissionTerms(rule, oldRule))
                    .map(rule -> new long[]{rule.installmentFrom(), rule.installmentTo()}).toList();
            if (!coversRange(oldRule.installmentFrom(), oldRule.installmentTo(), intervals)) {
                throw incomplete("commissionRules", "승계본에 기존 ACTIVE 수수료 규칙의 적용 범위·항목·회차가 빠져 있습니다.");
            }
        }
        for (FeeRegimeRuleDraft oldRule : previous.feeRegimeRules()) {
            if (!coversFeeRule(oldRule, replacement.feeRegimeRules().stream().filter(rule -> sameFeeTerms(rule, oldRule)).toList())) {
                throw incomplete("feeRegimeRules", "승계본에 기존 ACTIVE 분급 룰셋의 판매일·기초서류 적용 범위가 빠져 있습니다.");
            }
        }
    }

    /** 초안 DTO에 없는 부속 한도도 양방향으로 보존해야 기존 계약의 판정이 바뀌지 않는다. */
    public void validateFeeLimits(PolicyDraft previous, PolicyDraft replacement,
                                 Map<Long, Set<PolicyFeeLimit>> previousLimits,
                                 Map<Long, Set<PolicyFeeLimit>> replacementLimits) {
        for (FeeRegimeRuleDraft old : previous.feeRegimeRules()) {
            List<FeeRegimeRuleDraft> sameLimits = replacement.feeRegimeRules().stream()
                    .filter(rule -> sameFeeTerms(old, rule)
                            && limitsFor(old, previousLimits).equals(limitsFor(rule, replacementLimits))).toList();
            if (!coversFeeRule(old, sameLimits)) throw incompleteFeeLimits();
        }
        for (FeeRegimeRuleDraft rule : replacement.feeRegimeRules()) {
            if (previous.feeRegimeRules().stream().noneMatch(old -> feeScopesOverlap(rule, old))) continue;
            List<FeeRegimeRuleDraft> sameLimits = previous.feeRegimeRules().stream()
                    .filter(old -> sameFeeTerms(old, rule)
                            && limitsFor(old, previousLimits).equals(limitsFor(rule, replacementLimits))).toList();
            if (!coversFeeRule(rule, sameLimits)) throw incompleteFeeLimits();
        }
    }

    private static Set<PolicyFeeLimit> limitsFor(FeeRegimeRuleDraft rule, Map<Long, Set<PolicyFeeLimit>> limits) {
        return rule.feeRegimeRulesetId() == null ? Set.of() : limits.getOrDefault(rule.feeRegimeRulesetId(), Set.of());
    }

    private static FgcBusinessException incompleteFeeLimits() {
        return incomplete("feeRegimeRules", "승계본은 기존 분급 한도를 유지해야 합니다. 한도가 복제된 기존 행을 보존하세요.");
    }

    /** REG-22: 이미 적용하던 계약 범위에는 새 금액 조건이나 우선순위를 끼워 넣지 않는다. */
    private static void validateFinancialTerms(PolicyDraft previous, PolicyDraft replacement) {
        for (CommissionRuleDraft rule : replacement.commissionRules()) {
            if (previous.commissionRules().stream().noneMatch(old -> contractScopesOverlap(rule, old))) continue;
            List<long[]> sameTerms = previous.commissionRules().stream()
                    .filter(old -> sameCommissionTerms(rule, old))
                    .map(old -> new long[]{old.installmentFrom(), old.installmentTo()}).toList();
            if (!coversRange(rule.installmentFrom(), rule.installmentTo(), sameTerms)) {
                throw incomplete("commissionRules", "승계로 기존 계약의 금액·지급조건을 변경할 수 없습니다. 기존 규칙을 유지하세요.");
            }
        }
        for (FeeRegimeRuleDraft rule : replacement.feeRegimeRules()) {
            if (previous.feeRegimeRules().stream().noneMatch(old -> feeScopesOverlap(rule, old))) continue;
            List<FeeRegimeRuleDraft> sameTerms = previous.feeRegimeRules().stream()
                    .filter(old -> sameFeeTerms(rule, old)).toList();
            if (!coversFeeRule(rule, sameTerms)) {
                throw incomplete("feeRegimeRules", "승계로 기존 적용 범위의 분급 조건을 변경할 수 없습니다.");
            }
        }
        for (PolicyDraft.ParameterDraft old : previous.parameters()) {
            if (replacement.parameters().stream().noneMatch(value -> old.key().equals(value.key()) && sameValue(old.value(), value.value()))) {
                throw incomplete("parameters", "승계본은 기존 계약의 정책 파라미터 키와 값을 유지해야 합니다.");
            }
        }
    }

    private static boolean sameValue(JsonNode first, JsonNode second) {
        return first.isNumber() && second.isNumber() ? first.decimalValue().compareTo(second.decimalValue()) == 0 : first.equals(second);
    }

    private static boolean contractScopesOverlap(CommissionRuleDraft first, CommissionRuleDraft second) {
        return overlapsScope(first.insurerId(), second.insurerId())
                && overlapsScope(first.productOfferingId(), second.productOfferingId())
                && overlapsScope(first.organizationId(), second.organizationId());
    }

    private static boolean sameCommissionTerms(CommissionRuleDraft first, CommissionRuleDraft second) {
        return coversCommissionScope(first, second) && coversCommissionScope(second, first)
                && first.calculationType() == second.calculationType()
                && Objects.equals(first.basisCode(), second.basisCode())
                && sameDecimal(first.ratePct(), second.ratePct()) && sameDecimal(first.fixedAmount(), second.fixedAmount())
                && first.equalMonthlyYn() == second.equalMonthlyYn()
                && Objects.equals(first.paymentConditionCode(), second.paymentConditionCode())
                && Objects.equals(first.priorityNo(), second.priorityNo());
    }

    private static boolean sameDecimal(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private static boolean overlapsScope(Object first, Object second) {
        return first == null || second == null || Objects.equals(first, second);
    }

    private static boolean feeScopesOverlap(FeeRegimeRuleDraft first, FeeRegimeRuleDraft second) {
        return Objects.equals(first.feeRegimeCode(), second.feeRegimeCode())
                && overlapsScope(first.basicDocumentVersion(), second.basicDocumentVersion())
                && start(first.salesStartDateFrom()) <= end(second.salesStartDateTo())
                && start(second.salesStartDateFrom()) <= end(first.salesStartDateTo())
                && start(first.basicDocumentDateFrom()) <= end(second.basicDocumentDateTo())
                && start(second.basicDocumentDateFrom()) <= end(first.basicDocumentDateTo());
    }

    private static boolean sameFeeTerms(FeeRegimeRuleDraft first, FeeRegimeRuleDraft second) {
        return Objects.equals(first.feeRegimeCode(), second.feeRegimeCode())
                && Objects.equals(first.basicDocumentVersion(), second.basicDocumentVersion())
                && Objects.equals(first.maxInstallments(), second.maxInstallments())
                && Objects.equals(first.longTermMaintenanceFrom(), second.longTermMaintenanceFrom())
                && Objects.equals(first.longTermMaintenanceTo(), second.longTermMaintenanceTo())
                && first.reviewRequiredYn() == second.reviewRequiredYn();
    }

    private static void validateHeader(PolicyDraft previous, PolicyDraft replacement) {
        if (previous.policyType() != replacement.policyType()) {
            throw incomplete("policyType", "승계본은 기존 ACTIVE와 같은 정책 유형이어야 합니다.");
        }
        if (!Objects.equals(regime(previous), regime(replacement))) {
            throw incomplete("feeRegimeCode", "승계본은 기존 ACTIVE와 같은 수수료 체계여야 합니다.");
        }
        if (!coversScope(replacement.basicDocumentVersion(), previous.basicDocumentVersion())) {
            throw incomplete("basicDocumentVersion", "승계본은 기존 ACTIVE의 기초서류 범위를 모두 포함해야 합니다.");
        }
        if (!Objects.equals(replacement.effectiveFrom(), previous.effectiveFrom())) {
            throw incomplete("effectiveFrom", "승계본의 시작일은 기존 ACTIVE와 같아야 합니다. 과거 적용 범위를 확장하거나 누락할 수 없습니다.");
        }
        if (end(replacement.effectiveTo()) < end(previous.effectiveTo())) {
            throw incomplete("effectiveTo", "승계본은 기존 ACTIVE의 종료일(무기한 포함)을 모두 포함해야 합니다.");
        }
    }

    private static String regime(PolicyDraft draft) {
        return draft.feeRegimeCode() == null ? "CURRENT" : draft.feeRegimeCode();
    }

    private static boolean coversScope(Object replacement, Object previous) {
        return replacement == null || Objects.equals(replacement, previous);
    }

    private static boolean coversCommissionScope(CommissionRuleDraft replacement, CommissionRuleDraft previous) {
        return replacement.paymentStage() == previous.paymentStage()
                && Objects.equals(replacement.commissionItemId(), previous.commissionItemId())
                && replacement.feeComponentType() == previous.feeComponentType()
                && coversScope(replacement.insurerId(), previous.insurerId())
                && coversScope(replacement.productOfferingId(), previous.productOfferingId())
                && coversScope(replacement.organizationId(), previous.organizationId())
                && coversScope(replacement.agentRankCode(), previous.agentRankCode());
    }

    private static boolean coversFeeRule(FeeRegimeRuleDraft previous, List<FeeRegimeRuleDraft> replacements) {
        List<FeeRegimeRuleDraft> matching = replacements.stream()
                .filter(rule -> Objects.equals(rule.feeRegimeCode(), previous.feeRegimeCode())
                        && coversScope(rule.basicDocumentVersion(), previous.basicDocumentVersion())).toList();
        long from = start(previous.salesStartDateFrom()), to = end(previous.salesStartDateTo());
        TreeSet<Long> probes = new TreeSet<>();
        probes.add(from);
        for (FeeRegimeRuleDraft rule : matching) {
            LongStream.of(start(rule.salesStartDateFrom()), end(rule.salesStartDateTo()) + 1)
                    .filter(boundary -> boundary >= from && boundary <= to).forEach(probes::add);
        }
        for (long salesDate : probes) {
            List<long[]> documentDates = matching.stream()
                    .filter(rule -> start(rule.salesStartDateFrom()) <= salesDate && end(rule.salesStartDateTo()) >= salesDate)
                    .map(rule -> new long[]{start(rule.basicDocumentDateFrom()), end(rule.basicDocumentDateTo())}).toList();
            if (!coversRange(start(previous.basicDocumentDateFrom()), end(previous.basicDocumentDateTo()), documentDates)) return false;
        }
        return true;
    }

    private static boolean coversRange(long from, long to, List<long[]> ranges) {
        long next = from;
        for (long[] range : ranges.stream().sorted(Comparator.comparingLong(r -> r[0])).toList()) {
            if (range[1] < next) continue;
            if (range[0] > next) return false;
            next = range[1] + 1;
            if (next > to) return true;
        }
        return false;
    }

    private static long start(LocalDate value) { return (value == null ? LocalDate.MIN : value).toEpochDay(); }
    private static long end(LocalDate value) { return (value == null ? LocalDate.MAX : value).toEpochDay(); }

    private static FgcBusinessException incomplete(String field, String message) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, java.util.Map.of("field", field), message);
    }
}
