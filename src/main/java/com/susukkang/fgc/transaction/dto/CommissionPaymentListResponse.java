package com.susukkang.fgc.transaction.dto;

import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** IF-API-20 수수료 지급 건 목록 한 행. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommissionPaymentListResponse {
    private Long commissionTransactionId;
    private PaymentStage paymentStage;
    private String paymentStageLabel;
    private String sourceType;
    private String sourceTypeLabel;
    private String sourceBusinessKey;
    private LocalDate settlementMonth;
    private Long recipientAgentId;
    private String recipientAgentName;
    private Long commissionItemId;
    private String commissionItemCode;
    private String commissionItemName;
    private BigDecimal amount;
    private String cashflowType;
    private String cashflowTypeLabel;
    private CommissionPaymentStatus status;
    private String statusLabel;
    private Integer attributionCount;
    private BigDecimal differenceAmount;
}
