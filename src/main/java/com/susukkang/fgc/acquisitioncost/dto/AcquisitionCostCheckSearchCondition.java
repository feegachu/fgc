package com.susukkang.fgc.acquisitioncost.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import lombok.Getter;
import lombok.Setter;

/** FUN-062 결과 목록의 선택 검색 조건. */
@Getter
@Setter
public class AcquisitionCostCheckSearchCondition {
    private Long contractId;
    private String contractNo;
    private Long insurerId;
    private PaymentStage paymentStage;
    private AcquisitionCostResultStatus resultStatus;
    private String sort;
}
