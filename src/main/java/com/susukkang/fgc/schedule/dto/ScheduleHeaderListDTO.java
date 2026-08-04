package com.susukkang.fgc.schedule.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
//스케줄 헤더 DTO
public class ScheduleHeaderListDTO {
    private Long scheduleHeaderId;       // 스케줄 헤더 ID
    private String contractNo;           // 계약번호
    private PaymentStage paymentStage;   // 지급단계
    private String scheduleRegime;       // 적용체계
    private String schedulePurpose;      // 용도
    private Integer scheduleVersionNo;   // 스케줄 버전
    private String status;               // 스케줄 상태
    private Boolean activeYn;            // 현재 사용 여부
    private Integer lineCount;           // 전체 회차 수
    private BigDecimal expectedTotal;    // 예상 총액
    private Long policyVersionId;        // 정책 버전 ID
    private String policyVersion;        // 정책 버전 표시명
}