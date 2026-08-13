package com.susukkang.fgc.validation.dto;

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

    private Long scheduleHeaderId;
    private Long policyVersionId;
    private Integer scheduleVersion;
    private String scheduleStatus;

    private Integer activeHeaderCount;

    private Integer lineCount;
    private Integer distinctLineCount;
    private BigDecimal totalAmount;
}