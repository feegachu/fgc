package com.susukkang.fgc.agenthome.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * IF-API-74 설계사 홈 응답. 포트폴리오(기간·필터 적용)와 이번 달 수수료(asOf 월 기준)는 기준이 달라 따로 둔다.
 * 확인할 계약({@code priorityContracts})은 기간·보험사 필터와 무관하게 본인 범위 전체에서 고른다.
 */
public record AgentHomeResponse(
        LocalDate asOf,
        String period,
        Range range,
        Filters filters,
        List<PriorityContract> priorityContracts,
        int priorityContractTotal,
        MyCommission myCommission,
        Portfolio portfolio,
        Basis basis,
        String dataStatus) {

    public static final String AVAILABLE = "AVAILABLE";
    public static final String PARTIAL = "PARTIAL";

    public record Range(LocalDate from, LocalDate to) {
    }

    public record Filters(String insurerType, Long insurerId) {
    }

    public record Basis(LocalDate asOf, Range range, List<String> warnings) {
    }

    /** 금액이 없으면 null 이다. 0 으로 대체하지 않는다. */
    public record Bucket(BigDecimal amount, long count) {
    }

    public record MyCommission(String month, Bucket expected, Bucket held, Bucket clawback, String dataStatus) {
    }

    public record CodeCount(String code, String label, long count) {
    }

    public record InsurerCount(Long insurerId, String name, long contractCount) {
    }

    public record MonthlyCount(String month, long newContractCount, BigDecimal monthlyEquivalentFirstPremiumSum) {
    }

    public record Portfolio(long contractCount, List<InsurerCount> insurers, List<MonthlyCount> monthly,
                            List<CodeCount> categories, List<CodeCount> statuses) {
    }

    public record LastCheck(String result, OffsetDateTime checkedAt, LocalDate nextCheckDate) {
    }

    public record PriorityContract(Long contractId, String contractNo, String insurerName, String productName,
                                   String currentStatus, List<String> reasonCodes, LocalDate nextCheckDate,
                                   OffsetDateTime lastEventEffectiveAt, Long elapsedDays, String contextVersion,
                                   LastCheck lastCheck) {
    }
}
