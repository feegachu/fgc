package com.susukkang.fgc.report.dto;

import java.math.BigDecimal;

/** 계약 수는 조회 조건에 해당하는 명세의 중복 없는 계약 수다. 소속은 현재 등록 조직 기준이다. */
public record StatementAgentOverview(long agentId, String agentCode, String agentName,
                                     long branchId, String branchName, String organizationName,
                                     long contractCount, long entryCount, BigDecimal paymentAmount,
                                     BigDecimal heldAmount, BigDecimal adjustmentAmount,
                                     BigDecimal clawbackAmount, long missingAmountCount) {
}
