package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.util.DisplayFormat;

/** CAP-W01 설계사별 모니터링 지표 응답. 계약별 규제 판정과 구분해 사용한다. */
public record CapAgentSummaryResponse(
        Long agentId,
        String agentCode,
        String agentName,
        Long organizationId,
        String organizationCode,
        String organizationName,
        long contractCount,
        long limitAmountTotal,
        long includedAmountTotal,
        String usagePct,
        long violationCount,
        long warningCount,
        String worstContractNo,
        String worstUsagePct
) {
    public static CapAgentSummaryResponse from(CapAgentSummaryRow row) {
        return new CapAgentSummaryResponse(
                row.getAgentId(),
                row.getAgentCode(),
                row.getAgentName(),
                row.getOrganizationId(),
                row.getOrganizationCode(),
                row.getOrganizationName(),
                row.getContractCount(),
                DisplayFormat.won(row.getLimitAmountTotal()),
                DisplayFormat.won(row.getIncludedAmountTotal()),
                DisplayFormat.rate(row.getUsagePct()),
                row.getViolationCount(),
                row.getWarningCount(),
                row.getWorstContractNo(),
                DisplayFormat.rate(row.getWorstUsagePct()));
    }
}
