package com.susukkang.fgc.base.dto;

import java.time.LocalDate;

/**
 * 보험상품 판매버전 조회 SQL 결과를 서비스 계층으로 전달하는 내부 프로젝션이다.
 */
public record ProductRow(
        Long productOfferingId,
        String insurerProductCode,
        String standardProductCode,
        String productName,
        String productGroupCode,
        String offeringVersion,
        LocalDate salesStartDate,
        LocalDate salesEndDate,
        String basicDocumentVersion,
        LocalDate basicDocumentDate,
        String channelCode,
        boolean channelSpecialRuleYn,
        String feeRegimeCode,
        boolean standardDeduction80Yn,
        Integer paymentTermMonths
) {
}
