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

    /** 실제 적격 사건을 해당 사건의 유일 인덱스로 UPSERT하며 동일 재관측은 변경하지 않는다. */
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
                ON CONFLICT (status_event_id) DO UPDATE
                    SET source_version = EXCLUDED.source_version, event_date = EXCLUDED.event_date
                  WHERE candidate.source_version IS DISTINCT FROM EXCLUDED.source_version
                     OR candidate.event_date IS DISTINCT FROM EXCLUDED.event_date
                """).setParameter("eventIds", eventIds).executeUpdate();
    }

    /** 계약·기준일 잠금 후 최신 GA_TO_FC 원천을 같은 후보에 연결하며 과거 관측은 되돌리지 않는다. */
    public int captureArbitrageResult(long arbitrageCheckId) {
        entityManager.flush();
        // 원천 쓰기 순서와 관측 순서가 달라도 동일 계약·기준일의 최신 판정을 보존한다.
        // 별도 문장에서 잠금을 취득해야 대기 뒤 아래 SELECT가 새 커밋 snapshot을 읽는다.
        entityManager.createNativeQuery("""
                SELECT CAST(pg_advisory_xact_lock(hashtextextended(
                    'clawback-arbitrage:' || contract_id || ':' || as_of_date, 0)) AS text)
                  FROM fgc.arbitrage_check
                 WHERE arbitrage_check_id = :sourceId AND payment_stage = 'GA_TO_FC'
                """).setParameter("sourceId", arbitrageCheckId).getResultList();
        return entityManager.createNativeQuery("""
                INSERT INTO fgc.clawback_candidate AS candidate
                    (contract_id, trigger_type, arbitrage_check_id, source_version, event_date)
                SELECT a.contract_id, 'ARBITRAGE', a.arbitrage_check_id, md5(to_jsonb(a)::text), a.as_of_date
                  FROM fgc.arbitrage_check requested
                  CROSS JOIN LATERAL (
                      SELECT observed.* FROM fgc.arbitrage_check observed
                       WHERE observed.contract_id = requested.contract_id AND observed.as_of_date = requested.as_of_date
                         AND observed.payment_stage = requested.payment_stage
                       ORDER BY observed.arbitrage_check_id DESC LIMIT 1
                  ) a
                 WHERE requested.arbitrage_check_id = :sourceId AND requested.payment_stage = 'GA_TO_FC'
                   AND (EXISTS (SELECT 1 FROM fgc.arbitrage_check detected
                       WHERE detected.contract_id = a.contract_id AND detected.as_of_date = a.as_of_date
                         AND detected.payment_stage = a.payment_stage AND detected.result_status = 'CANDIDATE')
                   OR EXISTS (
                       SELECT 1 FROM fgc.clawback_candidate old
                       WHERE old.contract_id = a.contract_id AND old.event_date = a.as_of_date
                         AND old.status_event_id IS NULL))
                ON CONFLICT (contract_id, event_date) WHERE status_event_id IS NULL DO UPDATE
                    SET arbitrage_check_id = EXCLUDED.arbitrage_check_id, source_version = EXCLUDED.source_version
                  WHERE candidate.arbitrage_check_id < EXCLUDED.arbitrage_check_id
                     OR (candidate.arbitrage_check_id = EXCLUDED.arbitrage_check_id
                         AND candidate.source_version IS DISTINCT FROM EXCLUDED.source_version)
                """).setParameter("sourceId", arbitrageCheckId).executeUpdate();
    }
}
