package com.susukkang.fgc.validation.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 정책 스냅샷(policy_snapshot)에 담을 product_offering 1건 — "상품 적용 수수료체계".
 * cap 모듈의 CapRuleSetView/RefundRateTableView와 짝을 맞춘 이름이다.
 */
@Getter
@Setter
public class ProductOfferingSnapshotView {
    private Long productOfferingId;
    private Long productId;
    private String channelCode;
    private String feeRegimeCode;
    private Boolean standardDeduction80Yn;
}
