package com.susukkang.fgc.reconciliation.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 보험사→GA 대사의 실제 원수사 명세 귀속 원자행
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@Getter
@Setter
public class InsurerGaActualSourceRow {

    private Long transactionAttributionId;
    private Long commissionTransactionId;
    private Long journalHeaderId;
    private Long contractId;
    // 2026-08-13 yslee - 원수사 설계사코드의 내부 설계사 정규화 결과 추가
    // 기존 코드: sourceAgentCode 원문만 조회하고 매핑 결과를 전달하지 않음
    // 문제: 보험회사별 외부 코드를 내부 agent_id와 비교할 수 없음
    // 개선: 효력기간 내 코드 매핑 결과와 매핑 건수를 함께 전달해 불일치·매핑오류를 구분
    private Long actualAgentId;
    private Integer actualAgentMappingCount;
    private Long commissionItemId;
    private LocalDate settlementMonth;
    private LocalDate dueDate;
    private BigDecimal actualAmount;
    private String sourceBusinessKey;
    private String sourceAgentCode;
}
