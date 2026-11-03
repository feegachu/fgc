package com.susukkang.fgc.assistant.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record PaymentCalculationRequest(
        @NotBlank @Pattern(regexp = "assistant\\.v1") String contractVersion,
        @NotNull Kind kind, @NotNull Mode mode,
        @Valid Scope scope, JsonNode candidate, @NotNull @Min(0) @Max(9007199254740991L) Long clientRevision) {
    public enum Kind { EXPECTED_COMMISSION, ATTRIBUTION_BALANCE, CAP_STATUS, PAYMENT_CANDIDATE,
        RECONCILIATION_DIFFERENCE, ARBITRAGE_EXCESS, JOURNAL_CORRECTION_REVIEW }
    public enum Mode { EXISTING, HYPOTHETICAL }
    public record Scope(@Pattern(regexp = "[1-9][0-9]{0,18}") String paymentId) {
        @JsonAnySetter public void rejectUnknownScope(String name, Object value) { throw new IllegalArgumentException("Unsupported scope field"); }
    }
    @JsonAnySetter public void rejectUnknownField(String name, Object value) { throw new IllegalArgumentException("Unsupported calculation field"); }
}
