package com.susukkang.fgc.journal.repository;

import com.susukkang.fgc.journal.dto.JournalCorrectionHeaderRow;
import com.susukkang.fgc.journal.dto.JournalCorrectionLineRow;
import com.susukkang.fgc.journal.entity.JournalCorrectionGroup;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 원분개 잠금·정정그룹 저장·월별 채번 잠금을 호출자의 정정 트랜잭션에서 처리한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-28
 */
@Repository
@RequiredArgsConstructor
public class JournalCorrectionRepository {

    private final EntityManager entityManager;

    /** 검증 실행은 아직 미매핑이므로 native 조회를 유지하고 원분개 행만 잠근다. */
    public JournalCorrectionHeaderRow findHeaderForUpdate(Long journalHeaderId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT h.journal_header_id, h.journal_date, h.journal_type,
                       h.source_entity_type, h.source_entity_id, h.revision_no,
                       h.validation_run_id, vr.status AS validation_run_status,
                       h.contract_id, h.policy_version_id, h.status, h.description
                  FROM fgc.journal_header h
                  LEFT JOIN fgc.validation_run vr ON vr.validation_run_id = h.validation_run_id
                 WHERE h.journal_header_id = :journalHeaderId
                 FOR UPDATE OF h
                """).unwrap(NativeQuery.class);
        query.setParameter("journalHeaderId", journalHeaderId);
        query.addScalar("journal_header_id", Long.class);
        query.addScalar("journal_date", LocalDate.class);
        query.addScalar("journal_type", String.class);
        query.addScalar("source_entity_type", String.class);
        query.addScalar("source_entity_id", String.class);
        query.addScalar("revision_no", Integer.class);
        query.addScalar("validation_run_id", Long.class);
        query.addScalar("validation_run_status", String.class);
        query.addScalar("contract_id", Long.class);
        query.addScalar("policy_version_id", Long.class);
        query.addScalar("status", String.class);
        query.addScalar("description", String.class);
        return query.setTupleTransformer((row, aliases) -> {
            JournalCorrectionHeaderRow result = new JournalCorrectionHeaderRow();
            result.setJournalHeaderId((Long) row[0]);
            result.setJournalDate((LocalDate) row[1]);
            result.setJournalType((String) row[2]);
            result.setSourceEntityType((String) row[3]);
            result.setSourceEntityId((String) row[4]);
            result.setRevisionNo((Integer) row[5]);
            result.setValidationRunId((Long) row[6]);
            result.setValidationRunStatus((String) row[7]);
            result.setContractId((Long) row[8]);
            result.setPolicyVersionId((Long) row[9]);
            result.setStatus((String) row[10]);
            result.setDescription((String) row[11]);
            return result;
        }).uniqueResult();
    }

    public List<JournalCorrectionLineRow> findLines(Long journalHeaderId) {
        return entityManager.createQuery("""
                SELECT l.lineNo AS lineNo, l.journalAccountId AS journalAccountId,
                       l.debitAmount AS debitAmount, l.creditAmount AS creditAmount,
                       l.contractId AS contractId, l.agentId AS agentId,
                       cast(l.paymentStage as string) AS paymentStage,
                       l.commissionItemId AS commissionItemId, l.memo AS memo
                  FROM JournalLine l
                 WHERE l.journalHeaderId = :journalHeaderId
                 ORDER BY l.lineNo
                """, Tuple.class)
                .setParameter("journalHeaderId", journalHeaderId)
                .getResultList().stream()
                .map(row -> {
                    JournalCorrectionLineRow result = new JournalCorrectionLineRow();
                    result.setLineNo(row.get("lineNo", Integer.class));
                    result.setJournalAccountId(row.get("journalAccountId", Long.class));
                    result.setDebitAmount(row.get("debitAmount", BigDecimal.class));
                    result.setCreditAmount(row.get("creditAmount", BigDecimal.class));
                    result.setContractId(row.get("contractId", Long.class));
                    result.setAgentId(row.get("agentId", Long.class));
                    result.setPaymentStage(row.get("paymentStage", String.class));
                    result.setCommissionItemId(row.get("commissionItemId", Long.class));
                    result.setMemo(row.get("memo", String.class));
                    return result;
                }).toList();
    }

    public void insertGroup(JournalCorrectionGroup group) {
        entityManager.persist(group);
        // FK를 Long/String으로 매핑하므로 뒤따르는 IDENTITY 헤더 INSERT보다 그룹을 먼저 반영한다.
        entityManager.flush();
    }

    public void lockJournalNumbering(String lockKey) {
        entityManager.createNativeQuery("""
                SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:lockKey, 0))
                """)
                .setParameter("lockKey", lockKey)
                .getSingleResult();
    }
}
