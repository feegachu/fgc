package com.susukkang.fgc.exceptioncase.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ExceptionRecoveryRecheckRequest(
        @NotNull @Positive Long candidateId,
        @NotNull @Positive Long originalPaymentId,
        @NotNull @Positive Long deductionPaymentId) {}
