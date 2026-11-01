package com.susukkang.fgc.compare.service;

import com.susukkang.fgc.compare.dto.CompareRankingResponse.Evidence;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 설명 : 상품 비교 순위의 결정론 계산(FUN-079, D-01 가정 {@value #RULE_VERSION}). 금액은 지급행(회차) 단위로 원 단위 HALF_UP 한 뒤 합산한다.
 *
 * 보수적 순수령액 = 한도 반영 수령 예상액 − 환수 노출액(가정한 해지 시점의 환수 규칙 적용).
 * - 정렬: 보수적 순수령액 내림차순, 같으면 환수 노출액이 적은 쪽, 그래도 같으면 상품 판매버전 ID 오름차순이다(IF-API-77).
 * - 한도: 한도 산입 항목(cap_rule_item INCLUDED)의 첫 {@value #FIRST_YEAR_INSTALLMENTS}회차 지급행을 회차 순서로 쌓다가 한도(기준 보험료 × 한도 배수)를 넘는 부분은
 *   지급하지 않고 이월 예정액으로 따로 센다. 한도 영향(%)은 산입 항목의 한도 적용 전 수령 예상액 ÷ 한도 금액이다(소수 6자리 버림, 화면이 절사해 표시).
 *   산입 제외(EXCLUDED) 항목은 전액 지급하되 한도를 소진하지 않는다.
 *   공통 한도 배수만 쓰는 근사이며 해약환급금 가산·준법 공제는 반영하지 않는다 — D-01 에서 PROJECT_ASSUMPTION 으로 채택했다(REG-08).
 * - 환수 노출액: 한도 반영 지급행 금액 × 그 항목의 환수율(ClawbackRuleResolver)을 행마다 반올림해 합한다. 환수율이 없는 항목은 환수 대상이 아니다.
 */
public final class CompareRankingCalculator {

    /** 이 클래스가 구현한 순위 산식 버전. 설정(fgc.compare.rule-version)이 이 값과 같을 때만 순위를 만든다. 산식을 바꾸면 올린다. */
    public static final String RULE_VERSION = "compare-net-v1";

    /** 수령 예상액을 합산하는 회차 범위(1~12회차). */
    static final int FIRST_YEAR_INSTALLMENTS = 12;

    /**
     * 수수료 규칙 한 줄. 정률이면 ratePct, 정액이면 fixedAmount 를 쓴다. policyVersion 은 근거 표시용이다.
     * clawbackRatePct 는 이 수수료 항목의 환수율이며 null 이면 환수 대상이 아니다.
     */
    public record Rule(int installmentFrom, int installmentTo, BigDecimal ratePct, BigDecimal fixedAmount, String label,
                       String policyVersion, boolean capExempt, BigDecimal clawbackRatePct) {

        public Rule(int installmentFrom, int installmentTo, BigDecimal ratePct, BigDecimal fixedAmount, String label,
                    String policyVersion) {
            this(installmentFrom, installmentTo, ratePct, fixedAmount, label, policyVersion, false, null);
        }
    }

    /** 한도 규칙. limitMultiplier 는 기준 보험료에 곱하는 배수(12.0 = 1,200%)다. */
    public record CapRule(BigDecimal limitMultiplier, String policyVersion, Long capRuleSetId) {

        public CapRule(BigDecimal limitMultiplier, String policyVersion) {
            this(limitMultiplier, policyVersion, null);
        }
    }

    /** cap 은 상품에 적용되는 한도 규칙이며 없으면 한도를 반영하지 않는다. clawbackPolicyVersion 은 환수율을 읽은 환수 정책이다. */
    public record Candidate(long offeringId, String insurer, String product, List<Rule> rules, String clawbackPolicyVersion,
                            CapRule cap, String associationGrade) {
    }

    public record Result(Candidate candidate, long grossReceipt, long cappedReceipt, long carryover, long clawbackExposure,
                         long conservativeNet, Double capImpactPct, List<Evidence> evidence) {
    }

    /** exempt 는 cap_rule_item 에서 한도 산입 제외(EXCLUDED)로 정해진 항목이라 지급은 하되 한도를 소진하지 않는다. rule 은 규칙 순번이다. */
    private record Row(int installment, long amount, boolean exempt, int rule) {
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

    /** 한도를 적용한 지급액과 그 지급액에 대한 환수 노출액. 규칙별 환수액은 호출자가 넘긴 배열에 쌓는다. */
    private record Payout(long paid, long clawback) {
    }

    static Result calculate(Candidate candidate, long basePremium, LocalDate asOf) {
        CapRule cap = candidate.cap();
        BigDecimal base = BigDecimal.valueOf(basePremium);
        List<Evidence> evidence = new ArrayList<>();
        List<Row> rows = paymentRows(candidate.rules(), base, asOf, evidence);
        long gross = rows.stream().mapToLong(Row::amount).sum();
        long includedGross = rows.stream().filter(row -> !row.exempt()).mapToLong(Row::amount).sum();

        long capAmount = cap == null ? Long.MAX_VALUE : round(base.multiply(cap.limitMultiplier()));
        long[] clawbackByRule = new long[candidate.rules().size()];
        Payout payout = pay(rows, capAmount, candidate.rules(), clawbackByRule);
        long carryover = gross - payout.paid();
        // 표시용으로 반올림하지 않는다. 99.96%를 100.0%로 올리면 화면 절사 규칙과 초과 판정이 어긋난다.
        Double capImpact = cap == null || capAmount == 0 ? null
                : BigDecimal.valueOf(includedGross).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(capAmount), 6, RoundingMode.DOWN).doubleValue();

        evidence.add(capEvidence(cap, gross, payout.paid(), carryover, asOf));
        clawbackEvidence(candidate, clawbackByRule, asOf, evidence);
        return new Result(candidate, gross, payout.paid(), carryover, payout.clawback(), payout.paid() - payout.clawback(),
                capImpact, evidence);
    }

    /** 규칙마다 1~12회차 안의 지급행을 만들고, 규칙별 수령 예상액 근거를 evidence 에 더한다. 지급행은 회차 순이다. */
    private static List<Row> paymentRows(List<Rule> rules, BigDecimal base, LocalDate asOf, List<Evidence> evidence) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            int from = firstFrom(rule);
            int to = firstTo(rule);
            if (from > to) {
                continue;
            }
            long amount = rule.ratePct() != null ? round(base.multiply(rule.ratePct()).movePointLeft(2)) : round(rule.fixedAmount());
            for (int installment = from; installment <= to; installment++) {
                rows.add(new Row(installment, amount, rule.capExempt(), i));
            }
            String formula = rule.ratePct() != null
                    ? "%s %d~%d회차 · 기준 보험료 × %s%%".formatted(rule.label(), from, to, plain(rule.ratePct()))
                    : "%s %d~%d회차 · 정액".formatted(rule.label(), from, to);
            evidence.add(new Evidence("수령 예상액", formula, amount * (to - from + 1), "수수료 규칙", rule.policyVersion(), asOf));
        }
        rows.sort(Comparator.comparingInt(Row::installment));
        return rows;
    }

    /** 지급행을 회차 순서로 쌓다가 한도를 넘는 부분은 지급하지 않는다. 환수 노출액은 행마다 그 항목의 환수율로 반올림해 합하고 규칙별 금액은 clawbackByRule 에 더한다. */
    private static Payout pay(List<Row> rows, long capAmount, List<Rule> rules, long[] clawbackByRule) {
        long remaining = capAmount;
        long paid = 0;
        long clawback = 0;
        for (Row row : rows) {
            long payable = row.exempt() ? row.amount() : Math.min(row.amount(), remaining);
            if (!row.exempt()) {
                remaining -= payable;
            }
            paid += payable;
            BigDecimal clawbackRatePct = rules.get(row.rule()).clawbackRatePct();
            if (clawbackRatePct != null) {
                long amount = round(BigDecimal.valueOf(payable).multiply(clawbackRatePct).movePointLeft(2));
                clawbackByRule[row.rule()] += amount;
                clawback += amount;
            }
        }
        return new Payout(paid, clawback);
    }

    /** 환수 근거는 1~12회차에 지급행이 있는 규칙마다 한 줄이다. 환수율이 없는 항목은 대상이 아님을 남기고 금액은 비운다. */
    private static void clawbackEvidence(Candidate candidate, long[] clawbackByRule, LocalDate asOf, List<Evidence> evidence) {
        for (int i = 0; i < candidate.rules().size(); i++) {
            Rule rule = candidate.rules().get(i);
            if (firstFrom(rule) > firstTo(rule)) {
                continue;
            }
            String span = "%s %d~%d회차".formatted(rule.label(), firstFrom(rule), firstTo(rule));
            evidence.add(rule.clawbackRatePct() == null
                    ? new Evidence("환수 노출액", span + " · 환수 규칙 대상 아님", null, "환수 규칙", candidate.clawbackPolicyVersion(), asOf)
                    : new Evidence("환수 노출액", span + " · 한도 반영 지급액 × 환수율 %s%%".formatted(plain(rule.clawbackRatePct())),
                            clawbackByRule[i], "환수 규칙", candidate.clawbackPolicyVersion(), asOf));
        }
    }

    private static int firstFrom(Rule rule) {
        return Math.max(1, rule.installmentFrom());
    }

    private static int firstTo(Rule rule) {
        return Math.min(FIRST_YEAR_INSTALLMENTS, rule.installmentTo());
    }

    private static Evidence capEvidence(CapRule cap, long gross, long paid, long carryover, LocalDate asOf) {
        if (cap == null) {
            return new Evidence("한도 영향", "한도 규칙 자료 없음 — 한도 미반영", paid, "한도 규칙", null, asOf);
        }
        return new Evidence("한도 영향",
                "한도 %s%% = 기준 보험료 × %s배 · 한도 적용 전 %,d원 → 지급 %,d원, 이월 예정 %,d원"
                        .formatted(plain(cap.limitMultiplier().movePointRight(2)), plain(cap.limitMultiplier()), gross, paid, carryover),
                paid, "한도 규칙", cap.policyVersion(), asOf);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static long round(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
