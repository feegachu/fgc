package com.susukkang.fgc.acquisitioncost.service;

import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckResponse.AcquisitionCostExclusionLine;
import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckResponse.AcquisitionCostItem;
import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckResponse.AcquisitionCostLineGroup;
import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckResponse.AcquisitionCostReason;
import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostResultStatus;
import com.susukkang.fgc.common.util.MoneyUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostResultStatus.NORMAL;
import static com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostResultStatus.REVIEW_REQUIRED;
import static com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostResultStatus.VIOLATION;

/**
 * 설명 : FUN-062 계약체결비용 한도 판정. 입력만으로 결과를 정하는 순수 함수이며 DB를 읽지 않는다(REG-02·03·11, 운영정책서 제45조의2).
 * 선지급과 유지관리는 같은 기준액을 각자 비교한다. 한쪽에서 다른 쪽을 빼지 않는다. 한도율은 룰셋에서만 받으며 고정 비율을 두지 않는다.
 * 월 상한(REG-03 제1호·REG-05)과 유지 자격은 FUN-064 범위라 판정하지 않는다.
 */
public final class ContractAcquisitionCostValidator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** acquisition_cost_check_detail.usage_pct numeric(12,6)의 최댓값. 넘으면 사용률을 비운다(판정은 금액으로 한다). */
    private static final BigDecimal MAX_USAGE_PCT = new BigDecimal("999999.999999");

    enum Bucket {
        UPFRONT("선지급"), MAINTENANCE("유지관리"), INDIRECT("간접지원경비"),
        EXCLUSION_CANDIDATE("제외 후보"), UNCLASSIFIED("미분류");

        private final String label;

        Bucket(String label) {
            this.label = label;
        }
    }

    /**
     * @param evidenceRef 확정 지급에 연결된 제4-32조제7항제3호 증빙. 없으면 null
     */
    public record Line(Long scheduleLineId, Integer installmentNo, String itemCode, String itemName,
                       String feeComponentType, BigDecimal amount, String evidenceRef) {
    }

    /**
     * @param acquisitionCostAmount 계약체결비용 기준액. 입력이 부족하면 null
     * @param lines                 활성 예정 스케줄의 회차. 스케줄을 특정하지 못하면 null(빈 목록은 실제 0건)
     */
    public record Input(BigDecimal acquisitionCostAmount, BigDecimal upfrontLimitPct, BigDecimal maintenanceLimitPct,
                        List<Line> lines) {
    }

    public record Result(AcquisitionCostResultStatus status, AcquisitionCostItem upfront, AcquisitionCostItem maintenance,
                         BigDecimal indirectSupportAmount, BigDecimal unclassifiedAmount, List<AcquisitionCostExclusionLine> exclusions,
                         List<AcquisitionCostLineGroup> lineGroups, List<AcquisitionCostReason> reasons) {
    }

    /** 한 한도의 코드, 표시 이름, 룰셋 한도율. */
    private record Limit(String code, String label, BigDecimal pct) {
    }

    private ContractAcquisitionCostValidator() {
    }

    public static Result validate(Input input) {
        List<Line> upfront = new ArrayList<>();
        List<Line> maintenance = new ArrayList<>();
        List<AcquisitionCostExclusionLine> exclusions = new ArrayList<>();
        Map<List<Object>, List<Line>> groups = new LinkedHashMap<>();
        BigDecimal indirect = BigDecimal.ZERO;
        BigDecimal unclassified = BigDecimal.ZERO;
        BigDecimal unproven = BigDecimal.ZERO;
        List<Line> lines = input.lines() == null ? List.of() : input.lines();
        for (Line line : lines) {
            Bucket bucket = classify(line);
            groups.computeIfAbsent(List.of(bucket, line.itemCode(), String.valueOf(line.feeComponentType())),
                    key -> new ArrayList<>()).add(line);
            BigDecimal amount = MoneyUtil.roundWon(line.amount());
            switch (bucket) {
                case UPFRONT -> upfront.add(line);
                case MAINTENANCE -> maintenance.add(line);
                case INDIRECT -> {
                    maintenance.add(line);
                    indirect = indirect.add(amount);
                }
                case EXCLUSION_CANDIDATE -> {
                    exclusions.add(exclusionLine(line, amount));
                    if (line.evidenceRef() == null) {
                        upfront.add(line);
                        unproven = unproven.add(amount);
                    }
                }
                case UNCLASSIFIED -> unclassified = unclassified.add(amount);
            }
        }

        boolean scheduleKnown = input.lines() != null;
        List<AcquisitionCostReason> reasons = new ArrayList<>();
        if (unclassified.signum() > 0) {
            reasons.add(new AcquisitionCostReason("UNCLASSIFIED_ITEM",
                    "선지급이나 유지관리로 분류하지 못한 금액 " + won(unclassified) + "을 판정에 넣지 않았습니다."));
        }
        if (unproven.signum() > 0) {
            reasons.add(new AcquisitionCostReason("EXCLUSION_EVIDENCE_MISSING",
                    "제외 요건의 증빙이 없는 금액 " + won(unproven) + "을 선지급 총액에 넣었습니다."));
        }
        AcquisitionCostItem upfrontItem = item(new Limit("UPFRONT_TOTAL", "선지급 총액", input.upfrontLimitPct()),
                input.acquisitionCostAmount(), upfront, scheduleKnown, unclassified.signum() > 0 || unproven.signum() > 0, reasons);
        AcquisitionCostItem maintenanceItem = item(new Limit("MAINTENANCE_TOTAL", "유지관리 총액", input.maintenanceLimitPct()),
                input.acquisitionCostAmount(), maintenance, scheduleKnown, unclassified.signum() > 0, reasons);

        List<AcquisitionCostLineGroup> lineGroups = groups.values().stream().map(ContractAcquisitionCostValidator::group).toList();
        return new Result(overall(upfrontItem, maintenanceItem), upfrontItem, maintenanceItem, scheduleKnown ? indirect : null,
                scheduleKnown ? unclassified : null, exclusions, lineGroups, reasons);
    }

    /**
     * 분급 수수료는 항목코드와 수수료 구성으로 구분한다(운영정책서 제16조). 공통비는 유지관리수수료의 일부로 설정하는 간접지원경비이고
     * (REG-06B, SRC-001), 규칙이 정하지 않은 구성은 어느 쪽에도 넣지 않는다.
     */
    private static Bucket classify(Line line) {
        if ("NEWCOMER_SUPPORT".equals(line.itemCode()) || "NEWCOMER_SUPPORT".equals(line.feeComponentType())) {
            return Bucket.EXCLUSION_CANDIDATE;
        }
        if ("COMMON_COST".equals(line.itemCode()) || "COMMON_COST".equals(line.feeComponentType())) {
            return Bucket.INDIRECT;
        }
        if ("UPFRONT".equals(line.feeComponentType())) {
            return Bucket.UPFRONT;
        }
        if ("MAINTENANCE".equals(line.feeComponentType()) || "LONG_TERM_MAINTENANCE".equals(line.feeComponentType())) {
            return Bucket.MAINTENANCE;
        }
        return Bucket.UNCLASSIFIED;
    }

    private static AcquisitionCostExclusionLine exclusionLine(Line line, BigDecimal amount) {
        boolean excluded = line.evidenceRef() != null;
        return new AcquisitionCostExclusionLine(line.scheduleLineId(), line.installmentNo(), line.itemCode(),
                line.itemName(), amount, excluded, line.evidenceRef(), excluded
                ? "요건과 증빙을 갖춰 계산에서 제외했습니다."
                : "제외 요건의 증빙이 없어 선지급 총액에 넣었습니다.");
    }

    private static AcquisitionCostResultStatus overall(AcquisitionCostItem upfront, AcquisitionCostItem maintenance) {
        if (upfront.status() == VIOLATION || maintenance.status() == VIOLATION) return VIOLATION;
        if (upfront.status() == REVIEW_REQUIRED || maintenance.status() == REVIEW_REQUIRED) return REVIEW_REQUIRED;
        return NORMAL;
    }

    private static AcquisitionCostItem item(Limit rule, BigDecimal base, List<Line> counted, boolean scheduleKnown,
                                            boolean needsReview, List<AcquisitionCostReason> reasons) {
        String label = rule.label();
        BigDecimal limit = limitAmount(base, rule.pct());
        BigDecimal calculated = scheduleKnown ? MoneyUtil.sumRounded(counted.stream().map(Line::amount).toList()) : null;
        BigDecimal usage = usagePct(calculated, limit);

        if (limit != null && calculated != null && calculated.compareTo(limit) > 0) {
            Integer exceededFrom = firstExceeded(counted, limit);
            String reason = label + " " + won(calculated) + "이 한도 " + won(limit) + "을 "
                    + won(calculated.subtract(limit)) + " 초과합니다. " + exceededFrom + "회차부터 초과합니다.";
            reasons.add(new AcquisitionCostReason("LIMIT_EXCEEDED", reason));
            return new AcquisitionCostItem(rule.code(), label, base, rule.pct(), calculated, limit, usage, VIOLATION,
                    VIOLATION.label(), exceededFrom, reason);
        }
        if (rule.pct() == null) {
            reasons.add(new AcquisitionCostReason("LIMIT_RATE_MISSING", "규칙에 " + label + " 한도율이 없습니다."));
        }
        if (limit == null || calculated == null || needsReview) {
            return new AcquisitionCostItem(rule.code(), label, base, rule.pct(), calculated, limit, usage, REVIEW_REQUIRED,
                    REVIEW_REQUIRED.label(), null, reviewReason(base, rule.pct(), calculated));
        }
        return new AcquisitionCostItem(rule.code(), label, base, rule.pct(), calculated, limit, usage, NORMAL, NORMAL.label(),
                null, label + " " + won(calculated) + "이 한도 " + won(limit) + " 이내입니다.");
    }

    private static BigDecimal limitAmount(BigDecimal base, BigDecimal limitPct) {
        return base == null || limitPct == null ? null : base.multiply(limitPct).divide(HUNDRED, 0, RoundingMode.HALF_UP);
    }

    private static BigDecimal usagePct(BigDecimal calculated, BigDecimal limit) {
        if (limit == null || calculated == null || limit.signum() == 0) return null;
        BigDecimal usage = calculated.multiply(HUNDRED).divide(limit, 6, RoundingMode.HALF_UP);
        return usage.compareTo(MAX_USAGE_PCT) > 0 ? null : usage;
    }

    private static String reviewReason(BigDecimal base, BigDecimal limitPct, BigDecimal calculated) {
        if (base == null) return "계약체결비용 기준액이 없어 한도를 계산하지 못했습니다.";
        if (limitPct == null) return "규칙에 한도율이 없어 한도를 계산하지 못했습니다.";
        if (calculated == null) return "예정 스케줄을 특정하지 못해 계산액을 구하지 못했습니다.";
        return "금액을 확정할 수 없는 항목이 있어 검토가 필요합니다.";
    }

    private static Integer firstExceeded(List<Line> counted, BigDecimal limit) {
        BigDecimal cumulative = BigDecimal.ZERO;
        for (Line line : counted) {
            cumulative = cumulative.add(MoneyUtil.roundWon(line.amount()));
            if (cumulative.compareTo(limit) > 0) return line.installmentNo();
        }
        throw new IllegalStateException("한도를 넘은 합계에 초과 회차가 없습니다.");
    }

    private static AcquisitionCostLineGroup group(List<Line> lines) {
        Line first = lines.getFirst();
        Bucket bucket = classify(first);
        return new AcquisitionCostLineGroup(bucket.name(), bucket.label, first.itemCode(), first.itemName(), first.feeComponentType(),
                lines.stream().map(Line::installmentNo).min(Integer::compare).orElseThrow(),
                lines.stream().map(Line::installmentNo).max(Integer::compare).orElseThrow(), lines.size(),
                MoneyUtil.sumRounded(lines.stream().map(Line::amount).toList()));
    }

    private static String won(BigDecimal amount) {
        return String.format("%,d원", amount.toBigInteger());
    }
}
