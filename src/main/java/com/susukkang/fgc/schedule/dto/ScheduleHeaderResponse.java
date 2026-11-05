package com.susukkang.fgc.schedule.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import com.susukkang.fgc.schedule.code.SchedulePurpose;
import com.susukkang.fgc.schedule.code.ScheduleRegime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 예상 스케줄 목록 조회 응답 DTO
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-07
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleHeaderResponse {
    private Long contractId;
    private Long policyVersionId;
    private Integer installmentCount;
    private Integer maxInstallmentNo;
    public boolean getCanConfirm() {
        return "CURRENT".equals(scheduleRegime) && "OPERATIONAL".equals(schedulePurpose)
                && Boolean.TRUE.equals(activeYn) && status == ScheduleHeaderStatus.PLANNED;
    }
    @io.swagger.v3.oas.annotations.media.Schema(nullable = true, description = "확정 차단 코드. 확정 가능한 CURRENT 운영판은 null; 예정판은 FGC-IMPL-SCHEDULE_PREVIEW_ONLY")
    public String getConfirmBlockedReason() {
        if (!"CURRENT".equals(scheduleRegime)) return "FGC-IMPL-SCHEDULE_PREVIEW_ONLY";
        return getCanConfirm() ? null : "SCHEDULE_NOT_ACTIVE_PLANNED";
    }

    private Long scheduleHeaderId; // 스케줄 헤더 ID
    private String contractNo; // 화면에 표시할 계약 번호
    private String insurerName; // 보험회사명
    private String productName; // 상품명
    private PaymentStage paymentStage; // 지급 단계

    public String getPaymentStageLabel() {
        return paymentStage == null ? null : paymentStage.label();
    }

    private String scheduleRegime; // 적용 체계

    public String getScheduleRegimeLabel() {
        return ScheduleRegime.labelOf(scheduleRegime);
    }

    private String schedulePurpose; // 스케줄 용도

    public String getSchedulePurposeLabel() {
        return SchedulePurpose.labelOf(schedulePurpose);
    }

    private Integer scheduleVersionNo; // 스케줄 버전
    private ScheduleHeaderStatus status; // 스케줄 상태

    public String getStatusLabel() {
        return status == null ? null : status.label();
    }

    private Boolean activeYn; // 현재 사용 여부
    private Integer lineCount; // 전체 행 수
    private BigDecimal expectedTotal; // 예상 총액
    private String policyVersionLabel; // 정책 버전 표시값
    private String generationReason; //생성 사유
    private OffsetDateTime generatedAt; // 생성 일시
}
