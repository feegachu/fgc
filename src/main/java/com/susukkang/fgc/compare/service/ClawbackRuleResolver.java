package com.susukkang.fgc.compare.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 설명 : 상품 비교의 환수율을 환수 정책(policy_type=CLAWBACK)의 clawback_rule 에서 고른다(FUN-079 D-01).
 * 선택 기준은 FUN-055 환수액 계산(D-02, 운영정책서 제48조의3 · #766)과 같다.
 * - 수수료 항목마다 경과월 구간이 맞는 규칙 중 가장 구체적인 것(항목 &gt; 판매버전 &gt; 보험사 지정)을 쓴다.
 * - 정책에 규칙이 하나도 없는 항목은 그 정책의 환수 대상이 아니다(환수율 null = 0원, 근거에 표시).
 * - 비교는 사용자가 정책을 고르지 않는다. 맞는 정책이 둘 이상이면 같은 우선순위를 정책에 적용해 상품의 판매버전 전용 &gt;
 *   보험사 전용 &gt; 전체 정책 하나를 쓰고(D-01 가정), 같은 범위의 정책이 둘 이상이면 임의로 고르지 않고 제외한다.
 *   보험사·판매버전마다 환수율이 다르면 정책을 나눠 등록한다는 D-02 4항의 구조를 따른다.
 * 해약환급률(계약자 환급, REG-23)은 환수율(설계사 수수료 회수, REG-14)이 아니므로 쓰지 않는다.
 */
public final class ClawbackRuleResolver {

    /** FUN-055 가 계산할 수 있는 유일한 기준금액 코드(지급 귀속액). 비교에서는 한도를 반영한 지급행이 이에 해당한다. */
    static final String BASIS_CODE = "PAID_ATTRIBUTED_AMOUNT";

    /** 조회한 환수 규칙 한 줄. insurer/offeringSpecific 은 그 조건이 NULL(전체)이 아니라 지정돼 있다는 뜻이다. */
    public record RuleRow(long policyVersionId, String policyVersion, Long commissionItemId, boolean insurerSpecific,
                          boolean offeringSpecific, int monthFrom, int monthTo, String basisCode, BigDecimal ratePct,
                          String condition) {
    }

    /**
     * excludeReason 이 null 이면 ratesByItem 에 모든 수수료 항목의 환수율이 있다. 값이 null 인 항목은 환수 대상이 아니다.
     */
    public record Resolution(Map<Long, BigDecimal> ratesByItem, String policyVersion, String excludeReason) {

        static Resolution excluded(String reason) {
            return new Resolution(Map.of(), null, reason);
        }
    }

    private ClawbackRuleResolver() {
    }

    public static Resolution resolve(List<RuleRow> rows, Collection<Long> itemIds, int elapsedMonths) {
        if (rows.isEmpty()) {
            return Resolution.excluded("CLAWBACK_RATE_MISSING");
        }
        // 정책마다 이 상품에 맞춘 범위(판매버전 2·보험사 1·전체 0)가 가장 좁은 규칙으로 정책의 범위를 정하고, 가장 좁은 정책을 쓴다.
        Map<Long, Integer> scopeByPolicy = new HashMap<>();
        rows.forEach(r -> scopeByPolicy.merge(r.policyVersionId(), scope(r), Math::max));
        int narrowest = scopeByPolicy.values().stream().max(Integer::compare).orElseThrow();
        List<Long> policies = scopeByPolicy.entrySet().stream().filter(e -> e.getValue() == narrowest).map(Map.Entry::getKey).toList();
        if (policies.size() > 1) {
            return Resolution.excluded("CLAWBACK_RULE_AMBIGUOUS");
        }
        List<RuleRow> policyRows = rows.stream().filter(r -> r.policyVersionId() == policies.get(0)).toList();
        Map<Long, BigDecimal> rates = new HashMap<>();
        for (Long itemId : itemIds) {
            List<RuleRow> forItem = policyRows.stream()
                    .filter(r -> r.commissionItemId() == null || r.commissionItemId().equals(itemId)).toList();
            if (forItem.isEmpty()) {
                rates.put(itemId, null);
                continue;
            }
            List<RuleRow> inBand = forItem.stream()
                    .filter(r -> r.monthFrom() <= elapsedMonths && elapsedMonths <= r.monthTo())
                    .sorted(Comparator.comparingInt(ClawbackRuleResolver::specificity).reversed()).toList();
            if (inBand.isEmpty()) {
                return Resolution.excluded("CLAWBACK_RATE_MISSING");
            }
            if (inBand.size() > 1 && specificity(inBand.get(0)) == specificity(inBand.get(1))) {
                return Resolution.excluded("CLAWBACK_RULE_AMBIGUOUS");
            }
            RuleRow rule = inBand.get(0);
            if (!BASIS_CODE.equals(rule.basisCode()) || !emptyCondition(rule.condition())) {
                return Resolution.excluded("CLAWBACK_RULE_UNSUPPORTED");
            }
            rates.put(itemId, rule.ratePct());
        }
        return new Resolution(rates, policyRows.get(0).policyVersion(), null);
    }

    private static int specificity(RuleRow rule) {
        return (rule.commissionItemId() != null ? 4 : 0) + scope(rule);
    }

    private static int scope(RuleRow rule) {
        return (rule.offeringSpecific() ? 2 : 0) + (rule.insurerSpecific() ? 1 : 0);
    }

    /** 조건식(귀책 구분 등)이 있는 규칙은 FUN-055 처럼 계산하지 않는다. */
    private static boolean emptyCondition(String condition) {
        return condition == null || condition.replaceAll("\\s", "").equals("{}");
    }
}
