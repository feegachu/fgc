package com.susukkang.fgc.contract.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    private static final String OPEN_HOLD_OF_CONTRACT = """
            h.commission_transaction_id = :paymentId AND h.released_at IS NULL
               AND h.contract_status_event_id IN (SELECT e.contract_status_event_id FROM fgc.contract_status_event e
                                                   WHERE e.contract_id = :contractId)
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    /**
     * 이 계약의 상태 사건으로 걸린, 아직 해제되지 않은 보류가 있는 지급 건. 규칙 판단의 heldPaymentIds 로 쓴다.
     * 한 지급 건이 여러 계약에 귀속될 수 있으므로 다른 계약의 사건으로 걸린 보류는 이 계약의 보류로 보지 않는다.
     */
    public Set<Long> openHeldPaymentIds(Long contractId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT h.commission_transaction_id
                  FROM fgc.payment_hold h
                  JOIN fgc.contract_status_event e ON e.contract_status_event_id = h.contract_status_event_id
                  JOIN fgc.transaction_attribution ta ON ta.commission_transaction_id = h.commission_transaction_id
                 WHERE e.contract_id = ? AND ta.contract_id = ? AND h.released_at IS NULL
                """, Long.class, contractId, contractId));
    }

    /** 같은 지급 건·사건이면 아무것도 하지 않는다(멱등). */
    public int insert(Long paymentId, Long eventId, Long policyVersionId, String reasonCode) {
        return jdbc.update("""
                INSERT INTO fgc.payment_hold (commission_transaction_id, contract_status_event_id, policy_version_id, reason_code)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (commission_transaction_id, contract_status_event_id) DO NOTHING
                """, paymentId, eventId, policyVersionId, reasonCode);
    }

    /**
     * 재개 판단이 난 지급 건의 보류 중 이 계약의 사건으로 걸렸고 이 재개가 푸는 사유인 것만 해제한다.
     * 다른 계약의 사건으로 걸린 보류, 호출자가 허용하지 않은 사유, 이미 해제된 행은 건드리지 않는다.
     */
    public int releaseOpen(Long paymentId, Long contractId, Long releasedByEventId, Collection<String> reasonCodes) {
        if (reasonCodes.isEmpty()) {
            return 0;
        }
        return namedJdbc.update("""
                UPDATE fgc.payment_hold h SET released_at = clock_timestamp(), released_by_event_id = :releasedByEventId
                 WHERE h.reason_code IN (:reasonCodes) AND
                """ + OPEN_HOLD_OF_CONTRACT, openHolds(paymentId, contractId, reasonCodes)
                .addValue("releasedByEventId", releasedByEventId));
    }

    /** {@link #releaseOpen}이 풀 보류가 있는가. 쓰지 않으므로 처리 계획과 미리보기가 같은 범위로 판단한다. */
    public boolean hasOpenHold(Long paymentId, Long contractId, Collection<String> reasonCodes) {
        return !reasonCodes.isEmpty() && namedJdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM fgc.payment_hold h WHERE h.reason_code IN (:reasonCodes) AND
                """ + OPEN_HOLD_OF_CONTRACT + ")", openHolds(paymentId, contractId, reasonCodes), Boolean.class);
    }

    /** 이 사유들을 풀어도 이 계약의 사건으로 걸린 다른 사유의 보류가 남는가(미리보기의 변경 후 보류 금액). */
    public boolean hasOtherOpenHold(Long paymentId, Long contractId, Collection<String> reasonCodes) {
        return namedJdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM fgc.payment_hold h WHERE h.reason_code NOT IN (:reasonCodes) AND
                """ + OPEN_HOLD_OF_CONTRACT + ")", openHolds(paymentId, contractId, reasonCodes), Boolean.class);
    }

    private static MapSqlParameterSource openHolds(Long paymentId, Long contractId, Collection<String> reasonCodes) {
        return new MapSqlParameterSource()
                .addValue("paymentId", paymentId)
                .addValue("reasonCodes", reasonCodes)
                .addValue("contractId", contractId);
    }
}
