package com.susukkang.fgc.transaction.dto;

import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** IF-API-20 수수료 지급 건 목록 검색 조건. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommissionPaymentSearchCondition {
    /** yyyy-MM 형식. */
    private String settlementMonth;
    private PaymentStage paymentStage;
    private CommissionPaymentStatus status;
    private String sourceType;
    private Long insurerId;
    private String contractNo;
    private Long agentId;
    private Long commissionItemId;
    private Boolean noAttributionOnly;
}
