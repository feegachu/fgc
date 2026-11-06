package com.susukkang.fgc.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BranchDashboardResponse(LocalDate month, LocalDate asOf, List<BranchMetrics> branches) {
    public record BranchMetrics(long organizationId, String name, long registeredAgents, long activeAgents,
                                long newContracts, long validContracts, BigDecimal gaAmount,
                                BigDecimal agentAmount, long openExceptions) {}
}
