package com.susukkang.fgc.dashboard.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * IF-API-68(FGC-UI-DASH-W01) 관리자 홈 GA·설계사 현황.
 * 건수만 돌려준다. 계약별 1,200% 판정을 설계사 공용 잔액으로 합친 금액은 어디에도 없다(REG-08).
 */
public record HomeSummaryResponse(
        LocalDate asOf,
        HomeAgentCounts agents,
        List<HomeBranch> branches,
        long attentionTotal,
        List<HomeAttentionAgent> attentionAgents,
        String basis
) {
    public record HomeAgentCounts(long registered, long active, long terminated) {
    }

    public record HomeBranch(
            Long organizationId,
            String name,
            long agentCount,
            long activeContractCount,
            long attentionCount
    ) {
    }

    public record HomeAttentionAgent(
            Long agentId,
            String name,
            String code,
            String branch,
            String status,
            String statusLabel,
            boolean activeYn,
            long contractCount,
            List<HomeAttentionReason> reasons
    ) {
    }

    public record HomeAttentionReason(String code, String label, long count) {
    }
}
