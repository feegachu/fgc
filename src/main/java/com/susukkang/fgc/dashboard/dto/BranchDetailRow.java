package com.susukkang.fgc.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

public record BranchDetailRow(long id, String label, String detail, String status,
                              @Schema(nullable = true) BigDecimal value,
                              @Schema(nullable = true) BigDecimal secondaryValue) {}
