package com.susukkang.fgc.transaction.domain;

import com.susukkang.fgc.common.code.PaymentStage;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 수수료 지급 건 등록·수정 명령
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
@Getter
@Builder
public class CommissionPaymentCommand {

    @Setter
    private Long paymentId;
    private final String sourceType;
    private final String sourceBusinessKey;
    private final Integer paymentSequence;
    private final Long sourceContractId;
    private final Long agentId;
    private final Long commissionItemId;
    private final PaymentStage paymentStage;
    private final Long policyVersionId;
    private final LocalDate settlementMonth;
    private final LocalDate dueDate;
    private final BigDecimal amount;
    private final String cashflowType;
    private final String note;
    private final Long naturalContractId;
}
