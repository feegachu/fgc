package com.susukkang.fgc.cap.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** cap_check_detail 1행 INSERT 파라미터 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapCheckDetailInsertRow {
    private Long capCheckId;
    private int detailSeq;
    private Long commissionItemId;
    private Long scheduleLineId;
    private String classificationSnapshot;
    private BigDecimal amount;
    private String decisionReason;
}
