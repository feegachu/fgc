package com.susukkang.fgc.contract.dto;

import java.util.List;

/**
 * GET /api/v1/contracts/{id}/transactions 전체 응답 — CONT-W02 탭6 "지급·대사"가 읽는
 * 두 데이터소스(commission_transaction+transaction_attribution, reconciliation_result,
 * 화면정의서:565)를 함께 담는다(코드리뷰 반영 — IF-API-17 정의만으로는 대사 결과가
 * 빠져 있었다).
 */
public record ContractTransactionTabResponse(
        List<ContractTransactionResponse> transactions,
        List<ContractReconciliationResponse> reconciliations
) {
}
