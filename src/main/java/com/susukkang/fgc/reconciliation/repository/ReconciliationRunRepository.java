package com.susukkang.fgc.reconciliation.repository;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunInsertRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunRow;
import com.susukkang.fgc.reconciliation.entity.ReconciliationRun;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 설명 : 대사 실행 생성·조회·조건부 상태 전이와 검증 대상 잠금.
 * PostgreSQL 원천·집계·잠금 계약을 보존하며 타입 지정 projection으로 조회한다.
 *
 * @author C4t4ddict
 * @since 2026-10-05
 * @version 1.0
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReconciliationRunRepository {
    private final EntityManager entityManager;
    private final ReconciliationRunJpaRepository runs;

    public boolean existsActiveInsurer(Long insurerId) {
        return entityManager.createQuery("""
                SELECT COUNT(i) FROM Insurer i
                 WHERE i.insurerId = :insurerId AND i.activeYn = true
                """, Long.class).setParameter("insurerId", insurerId).getSingleResult() > 0;
    }

    @Transactional
    public int transitionToRunning(Long reconciliationRunId) {
        entityManager.flush();
        int affected = entityManager.createNativeQuery("""
                UPDATE fgc.reconciliation_run
                SET status = 'RUNNING',
                started_at = clock_timestamp(),
                completed_at = NULL
                WHERE reconciliation_run_id = :reconciliationRunId
                AND status IN ('CREATED', 'FAILED')
                """).setParameter("reconciliationRunId", reconciliationRunId).executeUpdate();
        detachRun(reconciliationRunId);
        return affected;
    }

    @Transactional
    public int transitionToCompleted(Long reconciliationRunId) {
        entityManager.flush();
        int affected = entityManager.createNativeQuery("""
                UPDATE fgc.reconciliation_run
                SET status = 'COMPLETED',
                completed_at = clock_timestamp()
                WHERE reconciliation_run_id = :reconciliationRunId
                AND status = 'RUNNING'
                """).setParameter("reconciliationRunId", reconciliationRunId).executeUpdate();
        detachRun(reconciliationRunId);
        return affected;
    }

    @Transactional
    public int transitionToFailed(Long reconciliationRunId) {
        entityManager.flush();
        int affected = entityManager.createNativeQuery("""
                UPDATE fgc.reconciliation_run
                SET status = 'FAILED',
                completed_at = clock_timestamp()
                WHERE reconciliation_run_id = :reconciliationRunId
                AND status = 'RUNNING'
                """).setParameter("reconciliationRunId", reconciliationRunId).executeUpdate();
        detachRun(reconciliationRunId);
        return affected;
    }

    public ReconciliationRunRow findById(Long reconciliationRunId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT reconciliation_run_id AS reconciliationRunId,
                validation_run_id AS validationRunId,
                settlement_month AS settlementMonth,
                payment_stage AS paymentStage,
                insurer_id AS insurerId,
                status,
                started_at AS startedAt,
                completed_at AS completedAt,
                created_by AS createdBy,
                created_at AS createdAt
                FROM fgc.reconciliation_run
                WHERE reconciliation_run_id = :reconciliationRunId
                """).unwrap(NativeQuery.class);
        query.setParameter("reconciliationRunId", reconciliationRunId);
        query.addScalar("reconciliationrunid", Long.class);
        query.addScalar("validationrunid", Long.class);
        query.addScalar("settlementmonth", LocalDate.class);
        query.addScalar("paymentstage", String.class);
        query.addScalar("insurerid", Long.class);
        query.addScalar("status", String.class);
        query.addScalar("startedat", OffsetDateTime.class);
        query.addScalar("completedat", OffsetDateTime.class);
        query.addScalar("createdby", Long.class);
        query.addScalar("createdat", OffsetDateTime.class);
        return query.setTupleTransformer((values, aliases) -> {
            ReconciliationRunRow row = new ReconciliationRunRow();
            row.setReconciliationRunId((Long) values[0]);
            row.setValidationRunId((Long) values[1]);
            row.setSettlementMonth((LocalDate) values[2]);
            row.setPaymentStage((String) values[3]);
            row.setInsurerId((Long) values[4]);
            row.setStatus((String) values[5]);
            row.setStartedAt((OffsetDateTime) values[6]);
            row.setCompletedAt((OffsetDateTime) values[7]);
            row.setCreatedBy((Long) values[8]);
            row.setCreatedAt((OffsetDateTime) values[9]);
            return row;
        }).uniqueResult();
    }

    public ReconciliationRunRow findByNaturalKey(LocalDate settlementMonth, PaymentStage paymentStage, Long insurerId, Long validationRunId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT reconciliation_run_id AS reconciliationRunId,
                validation_run_id AS validationRunId,
                settlement_month AS settlementMonth,
                payment_stage AS paymentStage,
                insurer_id AS insurerId,
                status,
                started_at AS startedAt,
                completed_at AS completedAt,
                created_by AS createdBy,
                created_at AS createdAt
                FROM fgc.reconciliation_run
                WHERE settlement_month = :settlementMonth
                AND payment_stage = :paymentStage
                AND insurer_id = :insurerId
                AND validation_run_id = :validationRunId
                """).unwrap(NativeQuery.class);
        query.setParameter("settlementMonth", settlementMonth);
        query.setParameter("paymentStage", paymentStage == null ? null : paymentStage.name());
        query.setParameter("insurerId", insurerId);
        query.setParameter("validationRunId", validationRunId);
        query.addScalar("reconciliationrunid", Long.class);
        query.addScalar("validationrunid", Long.class);
        query.addScalar("settlementmonth", LocalDate.class);
        query.addScalar("paymentstage", String.class);
        query.addScalar("insurerid", Long.class);
        query.addScalar("status", String.class);
        query.addScalar("startedat", OffsetDateTime.class);
        query.addScalar("completedat", OffsetDateTime.class);
        query.addScalar("createdby", Long.class);
        query.addScalar("createdat", OffsetDateTime.class);
        return query.setTupleTransformer((values, aliases) -> {
            ReconciliationRunRow row = new ReconciliationRunRow();
            row.setReconciliationRunId((Long) values[0]);
            row.setValidationRunId((Long) values[1]);
            row.setSettlementMonth((LocalDate) values[2]);
            row.setPaymentStage((String) values[3]);
            row.setInsurerId((Long) values[4]);
            row.setStatus((String) values[5]);
            row.setStartedAt((OffsetDateTime) values[6]);
            row.setCompletedAt((OffsetDateTime) values[7]);
            row.setCreatedBy((Long) values[8]);
            row.setCreatedAt((OffsetDateTime) values[9]);
            return row;
        }).uniqueResult();
    }

    public List<Long> findSelectedInsurerIds(Long validationRunId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT DISTINCT contract.insurer_id
                FROM fgc.validation_target target
                JOIN fgc.insurance_contract contract
                ON contract.contract_id = target.contract_id
                JOIN fgc.insurer insurer
                ON insurer.insurer_id = contract.insurer_id
                WHERE target.validation_run_id = :validationRunId
                AND target.selection_status = 'SELECTED'
                AND insurer.active_yn = TRUE
                ORDER BY contract.insurer_id
                """).unwrap(NativeQuery.class);
        query.setParameter("validationRunId", validationRunId);
        query.addScalar("insurer_id", Long.class);
        return query.setTupleTransformer((values, aliases) -> (Long) values[0]).getResultList();
    }

    public List<Long> findSelectedContractIds(Long validationRunId, Long insurerId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT target.contract_id
                FROM fgc.validation_target target
                JOIN fgc.insurance_contract contract
                ON contract.contract_id = target.contract_id
                JOIN fgc.insurer insurer
                ON insurer.insurer_id = contract.insurer_id
                WHERE target.validation_run_id = :validationRunId
                AND target.selection_status = 'SELECTED'
                AND contract.insurer_id = :insurerId
                AND insurer.active_yn = TRUE
                ORDER BY target.contract_id
                """).unwrap(NativeQuery.class);
        query.setParameter("validationRunId", validationRunId);
        query.setParameter("insurerId", insurerId);
        query.addScalar("contract_id", Long.class);
        return query.setTupleTransformer((values, aliases) -> (Long) values[0]).getResultList();
    }

    /** #379 공유 API 병합 후 소비 호출부를 전환할 임시 잠금 계약. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Long lockValidationRun(Long validationRunId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT validation_run_id
                FROM fgc.validation_run
                WHERE validation_run_id = :validationRunId
                FOR UPDATE
                """).unwrap(NativeQuery.class);
        query.setParameter("validationRunId", validationRunId);
        query.addScalar("validation_run_id", Long.class);
        return (Long) query.uniqueResult();
    }

    /** CREATED 생성에는 JpaRepository를 사용하고 DB 생성시각은 projection에서 읽는다. */
    @Transactional
    public void insert(ReconciliationRunInsertRow row) {
        var run = runs.saveAndFlush(com.susukkang.fgc.reconciliation.entity.ReconciliationRun.create(row));
        row.setReconciliationRunId(run.getReconciliationRunId());
        // 상태 변경은 조건부 native UPDATE이므로 새 엔티티를 관리 컨텍스트에 남기지 않는다.
        entityManager.detach(run);
    }

    /** 변경한 실행만 분리하여 다른 영역의 관리 엔티티를 유지하고 오래된 상태 재조회를 막는다. */
    private void detachRun(Long reconciliationRunId) {
        entityManager.detach(entityManager.getReference(ReconciliationRun.class, reconciliationRunId));
    }
}
