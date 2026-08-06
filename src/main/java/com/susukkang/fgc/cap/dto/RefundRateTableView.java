package com.susukkang.fgc.cap.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * refund_rate_table 헤더 1건 (+ 소속 policy_version 의 version_no)
 */
@Getter
@Setter
public class RefundRateTableView {
    private Long refundRateTableId;
    private Long policyVersionId;
    private Integer versionNo;
    private Boolean standardDeduction80Yn;
    private LocalDate effectiveFrom;
}
