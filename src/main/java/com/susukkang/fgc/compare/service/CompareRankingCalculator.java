package com.susukkang.fgc.compare.service;

import com.susukkang.fgc.compare.dto.CompareRankingResponse.Evidence;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 설명 : 상품 비교 순위의 결정론 계산(FUN-079 기획서 §5). 금액은 지급행(회차) 단위로 원 단위 HALF_UP 한 뒤 합산한다.
 *
 * 보수적 순수령액 = 한도 반영 수령 예상액 − 환수 노출액(clawbackMonth 에 해지된다고 가정).
 * - 정렬: 보수적 순수령액 내림차순, 같으면 환수 노출액이 적은 쪽, 그래도 같으면 상품 판매버전 ID 오름차순이다(IF-API-77).
 * - 한도: 한도 산입 항목(cap_rule_item INCLUDED)의 첫 {@value #FIRST_YEAR_INSTALLMENTS}회차 지급행을 회차 순서로 쌓다가 한도(기준 보험료 × 한도 배수)를 넘는 부분은
 *   지급하지 않고 이월 예정액으로 따로 센다. 한도 영향(%)은 산입 항목의 한도 적용 전 수령 예상액 ÷ 한도 금액이다.
 *   산입 제외(EXCLUDED) 항목은 전액 지급하되 한도를 소진하지 않는다.
 *   (결정 B 미결: cap 도메인 사전검증 로직 재사용 전까지는 공통 한도 배수만 쓰는 근사값이며, 해약환급금 가산·준법 공제는 반영하지 않는다.)
 * - 환수 노출액: 한도 반영 지급행 금액 × 환수율을 행마다 반올림해 합한다.
 *   **임시 규칙:** 환수율은 아직 구현 전인 환수 규칙(clawback_rule) 대신 그 상품 해약환급률표의 해당 차월 환급률을 쓴다.
 *   환수 규칙이 구현되면 {@link Candidate#clawbackRatePct()} 의 출처만 환수 규칙 조회로 바꾸면 된다.
 */
public final class CompareRankingCalculator {

    /** 수령 예상액을 합산하는 회차 범위(1~12회차). */
    static final int FIRST_YEAR_INSTALLMENTS = 12;

    /** 수수료 규칙 한 줄. 정률이면 ratePct, 정액이면 fixedAmount 를 쓴다. policyVersion 은 근거 표시용이다. */
    public record Rule(int installmentFrom, int installmentTo, BigDecimal ratePct, BigDecimal fixedAmount, String label,
                       String policyVersion, boolean capExempt) {

        public Rule(int installmentFrom, int installmentTo, BigDecimal ratePct, BigDecimal fixedAmount, String label,
                    String policyVersion) {
            this(installmentFrom, installmentTo, ratePct, fixedAmount, label, policyVersion, false);
        }
    }

    /** 한도 규칙. limitMultiplier 는 기준 보험료에 곱하는 배수(12.0 = 1,200%)다. */
    public record CapRule(BigDecimal limitMultiplier, String policyVersion, Long capRuleSetId) {

        public CapRule(BigDecimal limitMultiplier, String policyVersion) {
            this(limitMultiplier, policyVersion, null);
        }
    }

    /**
     * cap 은 상품에 적용되는 한도 규칙이며 없으면 한도를 반영하지 않는다.
     * clawbackRatePct 는 지금은 해약환급률표의 환급률이다(임시, 클래스 설명 참고). 자료가 없으면 null 이고 환수 0원으로 계산한다.
     */
    public record Candidate(long offeringId, String insurer, String product, List<Rule> rules, BigDecimal clawbackRatePct,
                            String clawbackPolicyVersion, CapRule cap, String associationGrade) {
    }

    public record Result(Candidate candidate, long grossReceipt, long cappedReceipt, long carryover, long clawbackExposure,
                         long conservativeNet, Double capImpactPct, List<Evidence> evidence) {
    }

    /** exempt 는 cap_rule_item 에서 한도 산입 제외(EXCLUDED)로 정해진 항목이라 지급은 하되 한도를 소진하지 않는다. */
    private record Row(int installment, long amount, boolean exempt) {
    }

    private CompareRankingCalculator() {
    }

    public static List<Result> rank(List<Candidate> candidates, long basePremium, LocalDate asOf) {
        return candidates.stream()
                .map(candidate -> calculate(candidate, basePremium, asOf))
                .sorted(Comparator.comparingLong(Result::conservativeNet).reversed()
                        .thenComparingLong(Result::clawbackExposure)
                        .thenComparingLong(result -> result.candidate().offeringId()))
                .toList();
    }

    static Result calculate(Candidate candidate, long basePremium, LocalDate asOf) {
        CapRule cap = candidate.cap();
        BigDecimal base = BigDecimal.valueOf(basePremium);
        List<Row> rows = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        for (Rule rule : candidate.rules()) {
            int from = Math.max(1, rule.installmentFrom());
            int to = Math.min(FIRST_YEAR_INSTALLMENTS, rule.installmentTo());
            if (from > to) {
                continue;
            }
            long ruleTotal = 0;
            for (int installment = from; installment <= to; installment++) {
                long amount = rule.ratePct() != null
                        ? round(base.multiply(rule.ratePct()).movePointLeft(2))
                        : round(rule.fixedAmount());
                rows.add(new Row(installment, amount, rule.capExempt()));
                ruleTotal += amount;
            }
            String formula = rule.ratePct() != null
                    ? "%s %d~%d회차 · 기준 보험료 × %s%%".formatted(rule.label(), from, to, plain(rule.ratePct()))
                    : "%s %d~%d회차 · 정액".formatted(rule.label(), from, to);
            evidence.add(new Evidence("수령 예상액", formula, ruleTotal, "수수료 규칙", rule.policyVersion(), asOf));
        }
        rows.sort(Comparator.comparingInt(Row::installment));
        long gross = rows.stream().mapToLong(Row::amount).sum();
        long includedGross = rows.stream().filter(row -> !row.exempt()).mapToLong(Row::amount).sum();

        long capAmount = cap == null ? Long.MAX_VALUE : round(base.multiply(cap.limitMultiplier()));
        long remaining = capAmount;
        long paid = 0;
        long clawback = 0;
        for (Row row : rows) {
            long payable = row.exempt() ? row.amount() : Math.min(row.amount(), remaining);
            if (!row.exempt()) {
                remaining -= payable;
            }
            paid += payable;
            if (candidate.clawbackRatePct() != null) {
                clawback += round(BigDecimal.valueOf(payable).multiply(candidate.clawbackRatePct()).movePointLeft(2));
            }
        }
        long carryover = gross - paid;
        Double capImpact = cap == null || capAmount == 0 ? null
                : BigDecimal.valueOf(includedGross).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(capAmount), 1, RoundingMode.HALF_UP).doubleValue();

        if (cap != null) {
            evidence.add(new Evidence("한도 영향",
                    "한도 %s%% = 기준 보험료 × %s배 · 한도 적용 전 %,d원 → 지급 %,d원, 이월 예정 %,d원"
                            .formatted(plain(cap.limitMultiplier().movePointRight(2)), plain(cap.limitMultiplier()), gross, paid, carryover),
                    paid, "한도 규칙", cap.policyVersion(), asOf));
        } else {
            evidence.add(new Evidence("한도 영향", "한도 규칙 자료 없음 — 한도 미반영", paid, "한도 규칙", null, asOf));
        }
        evidence.add(new Evidence("환수 노출액",
                candidate.clawbackRatePct() == null ? "해약환급률표 자료 없음 — 0원으로 계산"
                        : "지급액 × 해약환급률 %s%% (환수 규칙 구현 전 임시 기준)".formatted(plain(candidate.clawbackRatePct())),
                clawback, "해약환급률표", candidate.clawbackPolicyVersion(), asOf));
        return new Result(candidate, gross, paid, carryover, clawback, paid - clawback, capImpact, evidence);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static long round(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
