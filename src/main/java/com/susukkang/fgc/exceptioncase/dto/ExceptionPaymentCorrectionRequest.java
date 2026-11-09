package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;

public record ExceptionPaymentCorrectionRequest(
        @Positive Long paymentId,
        OffsetDateTime expectedUpdatedAt,
        @NotNull @Valid CommissionPaymentCreateRequest payment,
        @jakarta.validation.constraints.Pattern(regexp = "RECORD_ONLY|SETTLEMENT_COMPLETED") String correctionMode,
        @jakarta.validation.constraints.Size(max = 500) String settlementEvidence,
        @jakarta.validation.constraints.Pattern(regexp = "REDUCE|DEFER") String plannedAction,
        @jakarta.validation.constraints.DecimalMin("0.00") java.math.BigDecimal deferredAmount,
        java.time.LocalDate deferredPaymentDate
) {
    public ExceptionPaymentCorrectionRequest(Long paymentId, OffsetDateTime expectedUpdatedAt, CommissionPaymentCreateRequest payment) {
        this(paymentId, expectedUpdatedAt, payment, "RECORD_ONLY", null, null, null, null);
    }
    public ExceptionPaymentCorrectionRequest(Long paymentId, OffsetDateTime expectedUpdatedAt, CommissionPaymentCreateRequest payment, String correctionMode, String settlementEvidence) {
        this(paymentId, expectedUpdatedAt, payment, correctionMode, settlementEvidence, null, null, null);
    }
}
