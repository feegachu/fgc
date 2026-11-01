package com.susukkang.fgc.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * RPT-W02 설계사 수수료 명세 한 줄(IF-API-87). 지급·보류·조정·환수 중 한 유형의 원천 한 건이다.
 * amount 는 원천에 값이 없으면 null 이며 0 으로 대체하지 않는다. ADJUSTMENT 의 차감(DEDUCTION)은 음수다.
 */
public record StatementEntry(
        String entryKey,
        String entryType,
        Long contractId,
        String contractNo,
        String commissionItemCode,
        String commissionItemName,
        Integer installmentNo,
        LocalDate basisDate,
        String statusCode,
        String statusLabel,
        BigDecimal amount,
        Long policyVersionId,
        Source source,
        String dataStatus
) {
    public static final String AVAILABLE = "AVAILABLE";
    public static final String MISSING = "MISSING";

    public record Source(String entityType, Long id) {
    }
}
