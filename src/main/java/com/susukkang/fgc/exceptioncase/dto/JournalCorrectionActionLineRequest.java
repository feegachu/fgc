package com.susukkang.fgc.exceptioncase.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 설명 : IF-API-44A 재기표 라인 입력값
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
public record JournalCorrectionActionLineRequest(
        @Positive Integer originalLineNo,
        @NotBlank String accountCode,
        @NotNull @DecimalMin("0") @DecimalMax("9999999999999")
        @Digits(integer = 13, fraction = 2) BigDecimal debitAmount,
        @NotNull @DecimalMin("0") @DecimalMax("9999999999999")
        @Digits(integer = 13, fraction = 2) BigDecimal creditAmount,
        @Size(max = 500) String lineDescription
) {
}
