package com.susukkang.fgc.schedule.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 회차별 예상 스케줄 응답 DTO
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-09
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleLineResponse {
    private Integer lineNo; // 줄 번호
    private Integer installmentNo; // 회차
    private Integer contractMonthNo; // 계약 차월
    private LocalDate dueDate; // 지급 예정일
    private String commissionItemName; // 수수료 항목명
    private String recipientName; // 수령자명
    private String basisCode; // 기준 코드
    private BigDecimal basisAmount; // 기준 금액
    private String calculationType; // 계산 방식(RATE, FIXED)
    private BigDecimal ratePct; // 적용 요율
    private BigDecimal expectedAmount; // 예상 금액
    private String status; // 스케줄 상태
    private Long ruleRef; // 예상액 산출 근거 수수료 규칙 ID
}
