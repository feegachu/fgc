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
    // 2026-08-13 yslee - 실제 원수사 명세 회차 비교값 추가
    // 기존 코드: 실제 명세 DTO에 회차가 없어 예상 회차와의 일치 여부를 판정할 수 없음
    // 문제: 실제 14회차가 예상 13회차와 달라도 REVIEW_REQUIRED로만 남아 INSTALLMENT_MISMATCH 인수조건 미충족
    // 개선: 정규화된 commission_transaction.installment_no를 실제 회차로 전달
    private Integer actualInstallmentNo;
    private LocalDate settlementMonth;
    private LocalDate dueDate;
    private BigDecimal actualAmount;
    private String sourceBusinessKey;
    private String sourceAgentCode;
}
