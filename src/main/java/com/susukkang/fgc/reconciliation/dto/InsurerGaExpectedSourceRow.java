package com.susukkang.fgc.reconciliation.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 보험사→GA 대사의 예상 스케줄 원자행
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@Getter
@Setter
public class InsurerGaExpectedSourceRow {

    private Long scheduleLineId;
    private Long journalHeaderId;
    private Long contractId;
    // 2026-08-13 yslee - 원수사→GA 대사의 예상 모집 설계사 식별값 추가
    // 기존 코드: 예상 원천 DTO에 설계사 식별값이 없음
    // 문제: 실제 원수사 설계사코드와 비교할 수 없어 다른 설계사가 MATCHED로 처리될 수 있음
    // 개선: 계약의 모집 설계사 ID를 예상 수취인 식별값으로 전달
    private Long expectedAgentId;
    private Long commissionItemId;
    private Integer installmentNo;
    private LocalDate dueDate;
    private LocalDate dueMonth;
    private BigDecimal expectedAmount;
}
