package com.susukkang.fgc.schedule.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
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
    private Long scheduleHeaderId; // 스케줄 헤더 ID
    private String contractNo; // 화면에 표시할 계약 번호
    private PaymentStage paymentStage; // 지급 단계
    private String scheduleRegime; // 적용 체계
    private String schedulePurpose; // 스케줄 용도
    private Integer scheduleVersionNo; // 스케줄 버전
    private ScheduleHeaderStatus status; // 스케줄 상태
    private Boolean activeYn; // 현재 사용 여부
    private Integer lineCount; // 전체 회차 수
    private BigDecimal expectedTotal; // 예상 총액
    private String policyVersionLabel; // 정책 버전 표시값
    private String generationReason; //생성 사유
    private OffsetDateTime generatedAt; // 생성 일시
}
