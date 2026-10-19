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
    private static final String INSURER_ID_PARAM = "insurerId";
    private static final String RECONCILIATION_RUN_ID_PARAM = "reconciliationRunId";
    private static final String VALIDATION_RUN_ID_PARAM = "validationRunId";
    private static final String SETTLEMENT_MONTH_PARAM = "settlementMonth";
    private static final String PAYMENT_STAGE_PARAM = "paymentStage";
    private static final List<ReconciliationNativeProjection.Column> RUN_COLUMNS = List.of(
            new ReconciliationNativeProjection.Column(RECONCILIATION_RUN_ID_PARAM, Long.class),
            new ReconciliationNativeProjection.Column(VALIDATION_RUN_ID_PARAM, Long.class),
            new ReconciliationNativeProjection.Column(SETTLEMENT_MONTH_PARAM, LocalDate.class),
            new ReconciliationNativeProjection.Column(PAYMENT_STAGE_PARAM, String.class),
            new ReconciliationNativeProjection.Column(INSURER_ID_PARAM, Long.class),
            new ReconciliationNativeProjection.Column("status", String.class),
            new ReconciliationNativeProjection.Column("startedAt", OffsetDateTime.class),
            new ReconciliationNativeProjection.Column("completedAt", OffsetDateTime.class),
            new ReconciliationNativeProjection.Column("createdBy", Long.class),
            new ReconciliationNativeProjection.Column("createdAt", OffsetDateTime.class));
    private final EntityManager entityManager;
    private final ReconciliationRunJpaRepository runs;

    public boolean existsActiveInsurer(Long insurerId) {
        return entityManager.createQuery("""
                SELECT COUNT(i) FROM Insurer i
                 WHERE i.insurerId = :insurerId AND i.activeYn = true
                """, Long.class).setParameter(INSURER_ID_PARAM, insurerId).getSingleResult() > 0;
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
                """).setParameter(RECONCILIATION_RUN_ID_PARAM, reconciliationRunId).executeUpdate();
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
                """).setParameter(RECONCILIATION_RUN_ID_PARAM, reconciliationRunId).executeUpdate();
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
                """).setParameter(RECONCILIATION_RUN_ID_PARAM, reconciliationRunId).executeUpdate();
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
        query.setParameter(RECONCILIATION_RUN_ID_PARAM, reconciliationRunId);
        return ReconciliationNativeProjection.map(query, ReconciliationRunRow::new, RUN_COLUMNS).uniqueResult();
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
                AND NOT superseded_yn
                """).unwrap(NativeQuery.class);
        query.setParameter(SETTLEMENT_MONTH_PARAM, settlementMonth);
        query.setParameter(PAYMENT_STAGE_PARAM, paymentStage == null ? null : paymentStage.name());
        query.setParameter(INSURER_ID_PARAM, insurerId);
        query.setParameter(VALIDATION_RUN_ID_PARAM, validationRunId);
        return ReconciliationNativeProjection.map(query, ReconciliationRunRow::new, RUN_COLUMNS).uniqueResult();
    }

    // 2026-10-12 yslee - #516 대사할 보험사에 그 달 확정 지급이 있는 보험사를 합친다.
    // 기존 코드: SELECTED 계약의 보험사만 골랐다.
    // 문제: 선정 계약이 하나도 없는 보험사(시드 FGN01 8월)는 대사가 돌지 않아,
    //       해지 뒤 지급 같은 비유효 계약 지급(FUN-049)이 결과 없이 빠졌다.
    // 개선: SELECTED 계약의 보험사 ∪ 그 정산월·지급단계에 계약 귀속 확정 지급이 있는 보험사.
    //       보험사 열은 단계별 실제 원천 쿼리와 같게 고른다(INSURER_TO_GA는 ct.insurer_id,
    //       GA_TO_FC는 귀속 계약의 insurer_id). 원천 쿼리의 나머지 조건보다 넓은 상위집합이라
    //       후보가 없으면 결과 0건으로 완료될 뿐 빠뜨리지는 않는다.
    public List<Long> findReconciliationInsurerIds(
            Long validationRunId, LocalDate settlementMonth, PaymentStage paymentStage) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT scope.insurer_id
                FROM (
                SELECT contract.insurer_id
                FROM fgc.validation_target target
                JOIN fgc.insurance_contract contract
                ON contract.contract_id = target.contract_id
                WHERE target.validation_run_id = :validationRunId
                AND target.selection_status = 'SELECTED'
                UNION
                SELECT CASE WHEN ct.payment_stage = 'GA_TO_FC' THEN contract.insurer_id
                ELSE ct.insurer_id END
                FROM fgc.commission_transaction ct
                JOIN fgc.transaction_attribution ta
                ON ta.commission_transaction_id = ct.commission_transaction_id
                JOIN fgc.insurance_contract contract
                ON contract.contract_id = ta.contract_id
                WHERE ct.payment_stage = :paymentStage
                AND ct.settlement_month = :settlementMonth
                AND ct.cashflow_type = 'PAYMENT'
                AND ct.status = 'CONFIRMED'
                AND ta.attribution_scope = 'CONTRACT'
                ) scope
                JOIN fgc.insurer insurer
                ON insurer.insurer_id = scope.insurer_id
                WHERE insurer.active_yn = TRUE
                ORDER BY scope.insurer_id
                """).unwrap(NativeQuery.class);
        query.setParameter(VALIDATION_RUN_ID_PARAM, validationRunId);
        query.setParameter(SETTLEMENT_MONTH_PARAM, settlementMonth);
        query.setParameter(PAYMENT_STAGE_PARAM, paymentStage.name());
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
        query.setParameter(VALIDATION_RUN_ID_PARAM, validationRunId);
        query.setParameter(INSURER_ID_PARAM, insurerId);
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
        query.setParameter(VALIDATION_RUN_ID_PARAM, validationRunId);
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
