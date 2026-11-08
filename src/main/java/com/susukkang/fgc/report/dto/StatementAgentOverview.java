package com.susukkang.fgc.report.dto;

import java.math.BigDecimal;
import java.util.List;

/** 계약 수는 조회 조건에 해당하는 명세의 중복 없는 계약 수다. 소속은 현재 등록 조직 기준이다. */
public record StatementAgentOverview(long agentId, String agentCode, String agentName,
                                     long branchId, String branchName, String organizationName,
                                     long contractCount, long entryCount, BigDecimal paymentAmount,
                                     BigDecimal heldAmount, BigDecimal adjustmentAmount,
                                     BigDecimal clawbackAmount, long missingAmountCount,
                                     Long organizationId, Long parentOrganizationId, String rankCode,
                                     List<OrganizationNode> organizationPath) {
    public record OrganizationNode(long organizationId, Long parentId, String organizationName, String organizationType) { }
    public StatementAgentOverview(long agentId, String agentCode, String agentName, long branchId,
                                  String branchName, String organizationName, long contractCount, long entryCount,
                                  BigDecimal paymentAmount, BigDecimal heldAmount, BigDecimal adjustmentAmount,
                                  BigDecimal clawbackAmount, long missingAmountCount) {
        this(agentId, agentCode, agentName, branchId, branchName, organizationName, contractCount, entryCount,
                paymentAmount, heldAmount, adjustmentAmount, clawbackAmount, missingAmountCount, null, null, null, List.of());
    }
}
