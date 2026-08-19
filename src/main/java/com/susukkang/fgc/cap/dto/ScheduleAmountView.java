package com.susukkang.fgc.cap.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 계약월차 1~12에 속하는 예상 스케줄(schedule_line) 한 줄. 1,200% 산입 후보 원자행
 */
@Getter
@Setter
public class ScheduleAmountView {
    private Long scheduleLineId;
    private Long commissionItemId;
    private Integer contractMonthNo;
    private BigDecimal amount;
    private String evidenceRef;
}
