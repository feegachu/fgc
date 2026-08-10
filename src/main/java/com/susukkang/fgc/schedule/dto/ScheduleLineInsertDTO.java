package com.susukkang.fgc.schedule.dto;

import com.susukkang.fgc.common.code.CalculationType;
import com.susukkang.fgc.common.code.ScheduleLineStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 회차별 예상 스케줄 라인을 저장하기 위한 DTO.
 * 계산된 수수료 금액과 해당 계산의 기준 및 규칙 정보를 함께 전달한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-09
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleLineInsertDTO {
    private Long scheduleLineId; // 생성된 스케줄 라인 ID
    private Long scheduleHeaderId; // 스케줄 헤더 ID
    private Integer lineNo; // 줄 번호
    private Integer installmentNo; // 회차
    private Integer contractMonthNo; // 계약 차월
    private LocalDate dueDate; // 지급 예정일
    private Long commissionItemId; // 수수료 항목 ID
    private Long beneficiaryAgentId; // 수령자 설계사 ID
    private String basisCode; // 기준 코드
    private BigDecimal basisAmount; // 기준 금액
    private CalculationType calculationType; // 계산 방식(RATE, FIXED)
    private BigDecimal ratePct; // 적용 요율
    private BigDecimal fixedAmount; // 정액 금액
    private Integer roundingScale;      // 반올림 후 유지할 소수점 자릿수
    private RoundingMode roundingMode;        // 반올림 방식(HALF_UP)
    private BigDecimal expectedAmount; // 예상 금액
    private String paymentConditionCode; // 지급 조건 코드
    private ScheduleLineStatus lineStatus; // 스케줄 라인 상태
    private Long sourceCommissionRuleId; // 생성 근거 수수료 규칙 ID
}
