package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.contract.dto.ContractStatusEffect;
import com.susukkang.fgc.contract.dto.ContractStatusEffect.EntityType;
import com.susukkang.fgc.contract.entity.ContractStatusEvent;
import com.susukkang.fgc.contract.repository.ContractStatusEventRepository;
import com.susukkang.fgc.contract.repository.PaymentHoldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 설명 : FUN-028 지급 확정 게이트의 상태 사건 검사. 지급 확정 API 가 계약 행을 잠근 뒤에 부른다.
 * worker 가 같은 계약 잠금으로 처리하므로 처리 전·중·후 어느 시점에 확정을 요청해도 우회되지 않는다.
 *
 * <p>차단 사유: 미해제 보류(OPEN_HOLD), 보류 중인 연결 회차(HOLD_SCHEDULE_LINE), 끝나지 않은 상태 사건 후속 작업(FOLLOW_UP_PENDING),
 * 그리고 현재 계약 상태 이력으로 판단하면 보류 대상인데 아직 보류가 걸리지 않은 지급 건(CURRENT_STATUS — 처리 뒤에 만든 초안 등).
 *
 * @version 1.0
 * @since 2026-11-03
 */
@Component
@RequiredArgsConstructor
public class PaymentHoldGate {

    private static final String BLOCKERS = """
            SELECT 'OPEN_HOLD' AS code, h.contract_status_event_id AS ref_id
              FROM fgc.payment_hold h
             WHERE h.commission_transaction_id = ? AND h.released_at IS NULL
            UNION ALL
            SELECT 'HOLD_SCHEDULE_LINE', sl.schedule_line_id
              FROM fgc.transaction_attribution ta
              JOIN fgc.schedule_line sl ON sl.schedule_line_id = ta.schedule_line_id
             WHERE ta.commission_transaction_id = ? AND sl.line_status = 'HOLD'
            UNION ALL
            SELECT 'FOLLOW_UP_PENDING', j.contract_status_event_id
              FROM fgc.contract_event_job j
              JOIN fgc.contract_status_event e ON e.contract_status_event_id = j.contract_status_event_id
             WHERE j.state <> 'SUCCEEDED'
               AND e.contract_id IN (SELECT ta.contract_id FROM fgc.transaction_attribution ta
                                      WHERE ta.commission_transaction_id = ? AND ta.contract_id IS NOT NULL)
            """;

    private final JdbcTemplate jdbc;
    private final ContractStatusPaymentRule rule;
    private final ContractStatusEventRepository events;
    private final PaymentHoldRepository holds;

    public record Blocker(String code, long refId) {
        public String label() {
            return code + ":" + refId;
        }
    }

    public List<Blocker> blockers(Long paymentId) {
        List<Blocker> blockers = new ArrayList<>(jdbc.query(BLOCKERS,
                (rs, row) -> new Blocker(rs.getString("code"), rs.getLong("ref_id")), paymentId, paymentId, paymentId));
        if (blockers.isEmpty()) {
            blockers.addAll(currentStatusBlockers(paymentId));
        }
        return blockers;
    }

    /** 보류·중단 상태 사건이 있는 계약만 판단한다(없는 계약은 지급 대상이라 정책 해석도 하지 않는다). */
    private List<Blocker> currentStatusBlockers(Long paymentId) {
        List<Long> contractIds = jdbc.queryForList("""
                SELECT DISTINCT ta.contract_id FROM fgc.transaction_attribution ta
                 WHERE ta.commission_transaction_id = ? AND ta.contract_id IS NOT NULL
                """, Long.class, paymentId);
        List<Blocker> blockers = new ArrayList<>();
        for (Long contractId : contractIds) {
            List<ContractStatusEvent> timeline = events.findByContractIdOrderByEffectiveAtAscEventSeqAsc(contractId);
            if (timeline.stream().noneMatch(event -> PaymentHoldFollowUpProcessor.HOLD_TRIGGERS.contains(event.getNewStatus()))) {
                continue;
            }
            ContractStatusEvent latest = timeline.get(timeline.size() - 1);
            for (ContractStatusEffect effect : rule.decide(latest, holds.openHeldPaymentIds(contractId))) {
                if (effect.entityType() == EntityType.PAYMENT && paymentId.equals(effect.entityId())
                        && "HELD".equals(effect.nextState())) {
                    blockers.add(new Blocker("CURRENT_STATUS", latest.getContractStatusEventId()));
                }
            }
        }
        return blockers;
    }
}
