package com.susukkang.fgc.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DashboardCommissionResponse(
        List<MonthlyCommission> months
) {
    public record MonthlyCommission(
            LocalDate month,
            BigDecimal gaAmount,
            BigDecimal agentAmount
    ) {}
}
