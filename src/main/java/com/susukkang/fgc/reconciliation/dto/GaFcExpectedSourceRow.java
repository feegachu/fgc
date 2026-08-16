package com.susukkang.fgc.reconciliation.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : GA→FC 대사의 예상 지급 스케줄 원자행
 *
 * @author yslee
 * @since 2026-08-13
 * @version 1.2
 */
@Getter
@Setter
public class GaFcExpectedSourceRow {

    private Long scheduleLineId;
    private Long journalHeaderId;
    private Long contractId;
    private Long expectedAgentId;
    private Long commissionItemId;
    private Integer installmentNo;
    private LocalDate dueDate;
    private LocalDate dueMonth;
    private BigDecimal expectedAmount;
}
