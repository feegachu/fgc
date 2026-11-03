package com.susukkang.fgc.contract.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.Set;

/**
 * 설명 : 상태 사건으로 걸린 미확정 지급 건 보류(payment_hold, FUN-028). commission_transaction 상태는 건드리지 않는다.
 * 같은 지급 건·같은 사건은 1행이라 재처리해도 같은 결과로 수렴한다. 모든 값은 바인딩한다.
 *
 * @version 1.0
 * @since 2026-11-03
 */
@Repository
@RequiredArgsConstructor
public class PaymentHoldRepository {

    private final JdbcTemplate jdbc;

    /** 이 계약에 귀속된 지급 건 중 아직 해제되지 않은 보류가 있는 건. 규칙 판단의 heldPaymentIds 로 쓴다. */
    public Set<Long> openHeldPaymentIds(Long contractId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT h.commission_transaction_id
                  FROM fgc.payment_hold h
                  JOIN fgc.transaction_attribution ta ON ta.commission_transaction_id = h.commission_transaction_id
                 WHERE ta.contract_id = ? AND h.released_at IS NULL
                """, Long.class, contractId));
    }

    /** 같은 지급 건·사건이면 아무것도 하지 않는다(멱등). */
    public int insert(Long paymentId, Long eventId, Long policyVersionId, String reasonCode) {
        return jdbc.update("""
                INSERT INTO fgc.payment_hold (commission_transaction_id, contract_status_event_id, policy_version_id, reason_code)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (commission_transaction_id, contract_status_event_id) DO NOTHING
                """, paymentId, eventId, policyVersionId, reasonCode);
    }

    /** 납입 재개로 이 지급 건의 보류 사유가 모두 없어졌을 때 미해제 보류를 해제한다. 이미 해제된 행은 건드리지 않는다. */
    public int releaseOpen(Long paymentId, Long releasedByEventId) {
        return jdbc.update("""
                UPDATE fgc.payment_hold SET released_at = clock_timestamp(), released_by_event_id = ?
                 WHERE commission_transaction_id = ? AND released_at IS NULL
                """, releasedByEventId, paymentId);
    }
}
