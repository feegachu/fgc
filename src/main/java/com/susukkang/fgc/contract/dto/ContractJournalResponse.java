package com.susukkang.fgc.contract.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/v1/contracts/{id}/journals 응답의 분개 헤더 1건
 * 그 헤더에 속한 라인 전체(lines)와 차변·대변 합계·차액을 함께 담는다.
 */
@Getter
@Builder
public class ContractJournalResponse {
    private final Long journalHeaderId;
    private final String journalNo;
    private final LocalDate journalDate;
    private final String journalType;
    private final String sourceEntityType;
    private final String sourceEntityId;
    private final Integer revisionNo;
    private final Long policyVersionId;
    private final Long validationRunId;
    private final String status;
    private final String statusLabel;
    private final String description;
    private final BigDecimal debitTotal;
    private final BigDecimal creditTotal;
    private final BigDecimal differenceAmount;
    // 이슈 #108 요구사항 "차변 합계, 대변 합계, 차액, 지급단계"에 지급단계도 헤더 응답
    // 필드로 명시돼 있다 — payment_stage는 journal_line 컬럼이지만, 한 헤더의 모든
    // 라인이 같은 값을 가지므로(#85/#93 설계) 첫 라인 값을 헤더 대표값으로 올린다.
    private final String paymentStage;
    private final String paymentStageLabel;
    private final List<ContractJournalLineResponse> lines;
}
