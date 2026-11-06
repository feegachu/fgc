package com.susukkang.fgc.dashboard.dto;

import java.math.BigDecimal;

public record BranchDetailRow(long id, String label, String detail, String status,
                              BigDecimal value, BigDecimal secondaryValue) {}
