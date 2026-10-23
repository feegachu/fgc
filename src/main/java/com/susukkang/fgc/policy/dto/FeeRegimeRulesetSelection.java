package com.susukkang.fgc.policy.dto;

import com.susukkang.fgc.policy.code.FeeRegimeResolutionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 설명 : 분급 룰셋 조회·적용 서비스(#537)의 결과. 판정 결과(#536)와 선택된 룰셋 전체(헤더·한도 행)를 함께 담는다.
 * REVIEW_REQUIRED·NOT_APPLICABLE 은 예외로 바꾸지 않고 사유와 사용한 키 값을 그대로 담으며, 룰셋 값은 비어 있다.
 * 호출부는 {@link #isSelected()}가 아니면 확정 스케줄을 만들 재료가 없다.
 *
 * @param resolution 판정 결과 (상태·사유 코드·사용한 키 값·근거 REG)
 * @param ruleset    선택된 룰셋 헤더 (SELECTED 일 때만, 아니면 null)
 * @param limits     선택된 룰셋의 한도 행 (SELECTED 일 때만, 한도 코드 순)
 */
public record FeeRegimeRulesetSelection(
        FeeRegimeResolution resolution,
        Ruleset ruleset,
        List<RulesetLimit> limits
) {

    /**
     * 룰셋 헤더. 경과조치(부칙 제3조① 4년 특례)는 별도 행 없이 룰셋의 판매개시일 구간으로 표현한다(#534).
     * 지급률·매월 동일 금액 조건은 여기 없고 commission_rule 이 정한다.
     */
    public record Ruleset(
            Long rulesetId,
            Long policyVersionId,
            String feeRegimeCode,
            LocalDate salesStartDateFrom,
            LocalDate salesStartDateTo,      // 이 날짜(당일 포함)까지 판매를 시작한 상품에 적용 — 특례 종료일, 없으면 null
            String basicDocumentVersion,     // null 이면 모든 기초서류 버전
            LocalDate basicDocumentDateFrom,
            LocalDate basicDocumentDateTo,
            Integer maxInstallments,         // 법정 최대 회차
            Integer longTermMaintenanceFrom, // 장기유지 구간 시작 회차, 없으면 null
            Integer longTermMaintenanceTo
    ) {
    }

    public record RulesetLimit(
            String limitCode,                // UPFRONT_TOTAL · MAINTENANCE_MONTHLY · MAINTENANCE_TOTAL · LONG_TERM_MONTHLY
            BigDecimal limitPct,
            boolean includesIndirectExpense
    ) {
    }

    public boolean isSelected() {
        return resolution.status() == FeeRegimeResolutionStatus.SELECTED;
    }

    /** 한도 코드로 한도 행을 꺼낸다. 해당 한도 행이 없으면 비어 있다(#651·#652가 사용). */
    public Optional<RulesetLimit> limit(String limitCode) {
        return limits.stream().filter(l -> l.limitCode().equals(limitCode)).findFirst();
    }

    /** 이 룰셋의 특례기간(판매개시일 상한) 종료일. 없으면 비어 있다(#649가 사용). */
    public Optional<LocalDate> specialPeriodEnd() {
        return ruleset == null ? Optional.empty() : Optional.ofNullable(ruleset.salesStartDateTo());
    }
}
