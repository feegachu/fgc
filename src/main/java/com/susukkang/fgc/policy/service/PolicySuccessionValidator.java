package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.dto.PolicyDraft;
import com.susukkang.fgc.policy.dto.PolicyDraft.CommissionRuleDraft;
import com.susukkang.fgc.policy.dto.PolicyDraft.FeeRegimeRuleDraft;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.LongStream;

/** FUN-017·제49조: ACTIVE 교체본은 이전 버전의 유효 기간과 상세 선택 범위를 모두 포함한다. */
@Component
public class PolicySuccessionValidator {
    public void validate(PolicyDraft previous, PolicyDraft replacement) {
        validateHeader(previous, replacement);
        for (CommissionRuleDraft oldRule : previous.commissionRules()) {
            List<long[]> intervals = replacement.commissionRules().stream()
                    .filter(rule -> coversCommissionScope(rule, oldRule))
                    .map(rule -> new long[]{rule.installmentFrom(), rule.installmentTo()}).toList();
            if (!coversRange(oldRule.installmentFrom(), oldRule.installmentTo(), intervals)) {
                throw incomplete("commissionRules", "승계본에 기존 ACTIVE 수수료 규칙의 적용 범위·항목·회차가 빠져 있습니다.");
            }
        }
        for (FeeRegimeRuleDraft oldRule : previous.feeRegimeRules()) {
            if (!coversFeeRule(oldRule, replacement.feeRegimeRules())) {
                throw incomplete("feeRegimeRules", "승계본에 기존 ACTIVE 분급 룰셋의 판매일·기초서류 적용 범위가 빠져 있습니다.");
            }
        }
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
        if (start(replacement.effectiveFrom()) > start(previous.effectiveFrom())) {
            throw incomplete("effectiveFrom", "승계본의 시작일은 기존 ACTIVE의 시작일보다 늦을 수 없습니다.");
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
