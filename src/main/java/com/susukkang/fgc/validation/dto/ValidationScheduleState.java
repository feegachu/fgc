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

    /**
     * #380 — ValidationTargetRepository의 {@code @SqlResultSetMapping} 네이티브 쿼리가
     * enum/bigint 컬럼을 String/Long으로 돌려주므로, 그 결과를 이 DTO로 변환하는 전용 생성자.
     */
    public ValidationScheduleState(Long contractId, String paymentStage, Long scheduleHeaderId,
                                    Long policyVersionId, Integer scheduleVersion, String scheduleStatus,
                                    Long activeHeaderCount, Long lineCount, Long distinctLineCount,
                                    BigDecimal totalAmount) {
        this.contractId = contractId;
        this.paymentStage = PaymentStage.valueOf(paymentStage);
        this.scheduleHeaderId = scheduleHeaderId;
        this.policyVersionId = policyVersionId;
        this.scheduleVersion = scheduleVersion;
        this.scheduleStatus = scheduleStatus == null ? null : ScheduleHeaderStatus.valueOf(scheduleStatus);
        this.activeHeaderCount = activeHeaderCount == null ? null : Math.toIntExact(activeHeaderCount);
        this.lineCount = lineCount == null ? null : Math.toIntExact(lineCount);
        this.distinctLineCount = distinctLineCount == null ? null : Math.toIntExact(distinctLineCount);
        this.totalAmount = totalAmount;
    }
}
