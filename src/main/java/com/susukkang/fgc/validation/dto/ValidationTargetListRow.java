package com.susukkang.fgc.validation.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * VRUN-W02 ③ 대상 선별 결과 1행.
 * validation_target + insurance_contract(계약번호) + product_offering/product(상품 판매버전) 조인 투영
 */
@Getter
@Setter
public class ValidationTargetListRow {
    private Long validationTargetId;
    private String contractNo;
    private String selectionStatus;
    private String productName;
    private String offeringVersion;
    private Long refundRateTableId;
    private String selectionReason;
}
