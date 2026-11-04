package com.susukkang.fgc.assistant.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
public record CalculationRevalidationRequest(@NotBlank @Size(max=100) String calculationId,
        @NotBlank @Size(max=100) String stateToken,@NotNull @Valid PaymentCalculationRequest input) {}
