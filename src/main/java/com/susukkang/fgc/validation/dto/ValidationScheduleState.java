package com.susukkang.fgc.validation.dto;

import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import lombok.*;

import java.math.BigDecimal;

/**
 * 설명 : ValidationScheduleState
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-13
 */
@Getter @Setter @Builder
@AllArgsConstructor
@NoArgsConstructor
public class ValidationScheduleState {
    private Long contractId;
    private PaymentStage paymentStage;

    private Long scheduleHeaderId;
    private Long policyVersionId;
    private Integer scheduleVersion;
    private ScheduleHeaderStatus scheduleStatus;

    private Integer activeHeaderCount;

    private Integer lineCount;
    private Integer distinctLineCount;
    private BigDecimal totalAmount;
}
