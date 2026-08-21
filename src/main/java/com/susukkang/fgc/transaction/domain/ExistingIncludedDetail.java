package com.susukkang.fgc.transaction.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 설명 : 확정 사전검증(PRE_CONFIRM) 시점에 이미 산입 누계를 구성하고 있는
 *        확정 지급 건 귀속행 1건의 항목별 내역.
 *        findCapRuleSnapshot 의 existing_included_amount 합계를 행 단위로 풀어낸 것으로,
 *        cap_check_detail 에 저장돼 CAP-W02 계산근거의 "저장 산입금액 = 항목별 합계"
 *        정합을 만든다 (FUN-035).
 *
 * @author yslee
 * @version 1.0
 * @since 2026-08-21
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExistingIncludedDetail {
    private Long transactionAttributionId;
    private Long commissionItemId;
    private String itemCode;
    private String itemName;
    private String classificationSnapshot;
    /** cashflow_type=DEDUCTION 이면 음수로 내려온다 — cap_check_detail.amount 의 CHECK(>=0) 때문에 음수 행이 있으면 항목화 저장을 포기한다. */
    private BigDecimal amount;
    private String decisionReason;
    private String evidenceRef;
}
