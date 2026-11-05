package com.susukkang.fgc.assistant.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record PaymentCalculationRequest(
    @NotBlank @Pattern(regexp = "assistant\\.v1") String contractVersion,
    @NotNull Kind kind,
    @NotNull Mode mode,
    @Valid Scope scope,
    JsonNode candidate,
    @NotNull @Min(0) @Max(9007199254740991L) Long clientRevision) {
  public enum Kind {
    EXPECTED_COMMISSION,
    ATTRIBUTION_BALANCE,
    CAP_STATUS,
    PAYMENT_CANDIDATE,
    RECONCILIATION_DIFFERENCE,
    ARBITRAGE_EXCESS,
    JOURNAL_CORRECTION_REVIEW
  }

  public enum Mode {
    EXISTING,
    HYPOTHETICAL
  }

  public record Scope(
      @Pattern(regexp = "[1-9]\\d{0,18}") String paymentId,
      @Pattern(regexp = "[1-9]\\d{0,18}") String contractId,
      @Pattern(regexp = "\\d{4}-\\d{2}") String settlementMonth,
      com.susukkang.fgc.common.code.PaymentStage paymentStage,
      @Min(1) Integer installmentNo,
      @Pattern(regexp = "[1-9]\\d{0,18}") String commissionItemId,
      @Pattern(regexp = "[1-9]\\d{0,18}") String beneficiaryAgentId,
      java.time.LocalDate asOfDate,
      @Pattern(regexp = "[1-9]\\d{0,18}") String reconciliationResultId,
      Aggregation aggregation) {
    public enum Aggregation {
      SINGLE_LINE,
      MONTH_TOTAL
    }

    public Scope(String paymentId) {
      this(paymentId, null, null, null, null, null, null, null, null, null);
    }

    @JsonAnySetter
    public void rejectUnknownScope(String name, Object value) {
      throw new IllegalArgumentException("Unsupported scope field");
    }
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unsupported calculation field");
  }
}
