package com.susukkang.fgc.contract.dto;

import java.math.BigDecimal;

/**
 * 상태 사건 후속 처리기(FUN-028·029)가 지급 건·스케줄 회차에 낸 영향 또는 처리하지 않은 이유 1건.
 * worker가 처리 성공과 같은 트랜잭션에 contract_status_event_effect로 저장한다. 같은 사건·대상·작업은 1행이다.
 */
public record ContractStatusEffect(
        EntityType entityType,
        Long entityId,
        String previousState,
        String nextState,
        BigDecimal amount,
        Long policyVersionId,
        String reasonCode,
        String reason
) {
    public enum EntityType { PAYMENT, SCHEDULE_LINE }
}
