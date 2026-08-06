package com.susukkang.fgc.transaction.domain;

/**
 * 설명 : 지급 건 등록 시 참조하는 수수료 항목 정보
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
public record CommissionItemReference(
        Long commissionItemId,
        String cashflowType
) {
}
