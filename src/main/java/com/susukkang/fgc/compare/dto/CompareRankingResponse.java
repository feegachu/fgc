package com.susukkang.fgc.compare.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 서버가 확정한 상품 비교 순위(LLM 없이 완성). 화면은 표시만 하고 금액·순위를 다시 계산하지 않는다.
 * 금액은 원 단위 long, 한도 사용률은 소수 한 자리다. 요약 문장은 별도 explain 응답으로 받는다.
 */
public record CompareRankingResponse(
        /** explain 호출에 쓰는 이번 계산 결과 ID(본인 세션에서만 유효, 단기 보관). 순위가 없으면 null */
        String rankResultId,
        /** READY | RULE_NOT_CONFIGURED(승인된 계산 기준 미설정, items=[]) | INSUFFICIENT_DATA(자료 부족으로 순위 없음) */
        String status,
        String categoryCode,
        String categoryLabel,
        LocalDate asOf,
        /** 순위 계산 기준 버전(fgc.compare.rule-version). 미설정이면 null */
        String ruleVersion,
        /** 이번 계산에 쓴 수수료 정책 버전. 후보가 없으면 null */
        String policyVersion,
        /** 순위 계산에 쓴 설계사 본인 계약의 평균 월 환산 보험료. 계약이 없으면 null */
        Long basePremium,
        int clawbackMonth,
        List<String> warnings,
        CapSummary capSummary,
        List<Item> items,
        /** 자료가 없어 순위에서 뺀 상품과 사유. 결측을 0이나 최하위로 바꾸지 않는다 */
        List<Excluded> excludedItems,
        /** 이번 계산에 적용한 세부 조건(없으면 모두 비어 있음) */
        CompareRankingFilters filters) {

    public static final String READY = "READY";
    public static final String RULE_NOT_CONFIGURED = "RULE_NOT_CONFIGURED";
    public static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";

    public record Excluded(long offeringId, String insurer, String product, List<String> reasonCodes) {
    }

    public record CapSummary(Double usagePct, String status, int contractCount) {
    }

    public record Item(
            int rank,
            long offeringId,
            String insurer,
            String product,
            /** 한도 적용 전 수령 예상액 */
            long expectedReceipt,
            /** 한도를 반영해 실제 지급되는 수령 예상액 */
            long cappedReceipt,
            /** 한도 초과로 이월되는 예정액 */
            long carryover,
            long clawbackExposure,
            /** 보수적 순수령액 = 한도 반영 수령 예상액 − 환수 노출액 */
            long conservativeNet,
            /** 환수 기준으로 쓴 환급률. 자료가 없으면 null */
            Double clawbackRatePct,
            /** 한도 적용 전 수령 예상액 ÷ 한도 금액(%). 한도 규칙이 없으면 null */
            Double capImpactPct,
            String associationGrade,
            List<Evidence> evidence) {
    }

    public record Evidence(String label, String rule, Long value, String source, String policyVersion, LocalDate asOf) {
    }
}
