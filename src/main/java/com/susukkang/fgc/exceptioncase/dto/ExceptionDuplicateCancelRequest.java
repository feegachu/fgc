package com.susukkang.fgc.exceptioncase.dto;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
public record ExceptionDuplicateCancelRequest(@NotNull @Positive Long paymentId, @NotNull OffsetDateTime expectedUpdatedAt,
        @Size(max=500) String evidenceRef) {}
