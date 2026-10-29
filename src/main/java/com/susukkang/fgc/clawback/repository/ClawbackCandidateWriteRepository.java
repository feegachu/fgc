package com.susukkang.fgc.clawback.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 원천을 직접 읽는 UPSERT. 전달받은 계약/날짜/정책을 신뢰해 가짜 후보를 만들지 않는다. */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ClawbackCandidateWriteRepository {
    private final EntityManager entityManager;

    public int captureStatusEvents(List<Long> eventIds) {
        if (eventIds.isEmpty()) return 0;
        entityManager.flush();
        return entityManager.createNativeQuery("""
                INSERT INTO fgc.clawback_candidate AS candidate
                    (contract_id, trigger_type, status_event_id, source_version, event_date)
                SELECT e.contract_id, 'STATUS_EVENT', e.contract_status_event_id, md5(to_jsonb(e)::text),
                       CAST(e.effective_at AT TIME ZONE 'Asia/Seoul' AS date)
                  FROM fgc.contract_status_event e
                 WHERE e.contract_status_event_id IN (:eventIds)
                   AND e.new_status IN ('TERMINATED', 'LAPSED', 'CANCELLED')
                ON CONFLICT (trigger_type, source_id, contract_id) DO UPDATE
                    SET source_version = EXCLUDED.source_version, event_date = EXCLUDED.event_date
                  WHERE candidate.source_version IS DISTINCT FROM EXCLUDED.source_version
                     OR candidate.event_date IS DISTINCT FROM EXCLUDED.event_date
                """).setParameter("eventIds", eventIds).executeUpdate();
    }

    public int captureArbitrageResult(long arbitrageCheckId) {
        entityManager.flush();
        return entityManager.createNativeQuery("""
                INSERT INTO fgc.clawback_candidate AS candidate
                    (contract_id, trigger_type, arbitrage_check_id, source_version, event_date)
                SELECT a.contract_id, 'ARBITRAGE', a.arbitrage_check_id, md5(to_jsonb(a)::text), a.as_of_date
                  FROM fgc.arbitrage_check a
                 WHERE a.arbitrage_check_id = :sourceId AND a.payment_stage = 'GA_TO_FC'
                   AND (a.result_status = 'CANDIDATE' OR EXISTS (
                       SELECT 1 FROM fgc.clawback_candidate old WHERE old.arbitrage_check_id = a.arbitrage_check_id))
                ON CONFLICT (trigger_type, source_id, contract_id) DO UPDATE
                    SET source_version = EXCLUDED.source_version, event_date = EXCLUDED.event_date
                  WHERE candidate.source_version IS DISTINCT FROM EXCLUDED.source_version
                     OR candidate.event_date IS DISTINCT FROM EXCLUDED.event_date
                """).setParameter("sourceId", arbitrageCheckId).executeUpdate();
    }
}
