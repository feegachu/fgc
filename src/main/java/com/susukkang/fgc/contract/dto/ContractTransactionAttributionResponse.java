package com.susukkang.fgc.contract.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * GET /api/v1/contracts/{id}/transactions 응답의 귀속행 1건.
 *
 * agentName은 마스킹하지 않는다(코드리뷰 반영) — 화면정의서 §4-11 개인정보 규칙은
 * "계약자" 전용이고, GET /api/v1/base/agents·CONT-W03 "모집 설계사"는 agent_name을
 * 원문 그대로 노출한다. 마스킹 정책이 정해지면 com.susukkang.fgc.common.util.
 * PersonalInfoMasker를 다시 적용한다.
 */
@Getter
@Builder
public class ContractTransactionAttributionResponse {
    private final Long transactionAttributionId;
    private final BigDecimal attributedAmount;
    private final LocalDate attributionDate;
    private final String inclusionStatus;
    private final String inclusionStatusLabel;
    private final Long agentId;
    private final String agentName;
    private final String agentCode;
}
