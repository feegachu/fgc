package com.susukkang.fgc.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 계약 기준 commission_transaction + transaction_attribution(+ agent) 조인 조회 결과 1행
 */
@Getter
@Setter
public class ContractTransactionAttributionRow {
    private Long commissionTransactionId;
    private LocalDate settlementMonth;
    private String paymentStage;
    private BigDecimal amount;
    private String status;
    private String sourceType;
    private Long transactionAttributionId;
    private BigDecimal attributedAmount;
    private LocalDate attributionDate;
    private String inclusionStatus;
    private Long agentId;
    private String agentName;
    private String agentCode;
    // 이 지급 건 전체(계약 무관)의 귀속 합계 — differenceAmount 계산용(코드리뷰 반영,
    // Mapper XML의 상관 서브쿼리 주석 참고). attributedAmount 합계(이 계약 몫)와는 다르다.
    private BigDecimal transactionAttributedTotal;
}
