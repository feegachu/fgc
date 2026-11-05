package com.susukkang.fgc.validation.dto;

import jakarta.validation.constraints.*;

public record ValidationRestartRequest(
    @NotNull @Positive Long expectedBatchExecutionId,
    @NotBlank @Size(max = 200) String expectedStateVersion,
    @NotBlank @Size(max = 500) String reason) {}
