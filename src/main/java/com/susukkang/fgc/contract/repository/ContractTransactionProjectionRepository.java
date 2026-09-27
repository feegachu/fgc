package com.susukkang.fgc.contract.repository;

import com.susukkang.fgc.contract.dto.ContractReconciliationRow;
import com.susukkang.fgc.contract.dto.ContractTransactionAttributionRow;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 설명 : 계약 상세의 지급·대사 탭에 필요한 지급 귀속행과 대사 결과를 조회한다.
 * 거래·대사 도메인 엔티티 없이 네이티브 SQL 결과를 계약 조회용 DTO로 반환한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContractTransactionProjectionRepository {

    private final EntityManager entityManager;

    /**
     * 설명 : 계약의 지급 귀속행을 지급 건 ID·귀속 순번 순으로 조회한다.
     * transactionAttributedTotal은 계약 범위와 무관한 지급 건 전체의 귀속 합계다.
     *
     * @param contractId 보험계약 ID
     * @return 지급 귀속행별 조회 결과. 귀속된 지급 건이 없으면 빈 목록
     */
    public List<ContractTransactionAttributionRow> findTransactionAttributionsByContractId(Long contractId) {
        // 1. 이 계약의 귀속행과 지급 건·설계사 정보를 함께 조회
        // 상관 서브쿼리는 계약으로 좁히지 않고 모든 attribution_scope를 합산한다.
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT ct.commission_transaction_id, ct.settlement_month, ct.payment_stage,
                       ct.amount, ct.status, ct.source_type,
                       ta.transaction_attribution_id, ta.attributed_amount, ta.attribution_date,
                       ta.inclusion_status_snapshot AS inclusion_status,
                       ag.agent_id, ag.agent_name, ag.agent_code,
                       (SELECT COALESCE(SUM(ta2.attributed_amount), 0)
                          FROM fgc.transaction_attribution ta2
                         WHERE ta2.commission_transaction_id = ct.commission_transaction_id
                       ) AS transaction_attributed_total
                  FROM fgc.transaction_attribution ta
                  JOIN fgc.commission_transaction ct
                    ON ct.commission_transaction_id = ta.commission_transaction_id
                  LEFT JOIN fgc.agent ag ON ag.agent_id = ta.agent_id
                 WHERE ta.contract_id = :contractId
                 ORDER BY ct.commission_transaction_id, ta.attribution_seq
                """).unwrap(NativeQuery.class);
        query.setParameter("contractId", contractId);

        // 2. 금액·날짜·ID 타입을 지정하고 설계사가 없는 귀속행의 null 값도 유지
        query.addScalar("commission_transaction_id", Long.class);
        query.addScalar("settlement_month", LocalDate.class);
        query.addScalar("payment_stage", String.class);
        query.addScalar("amount", BigDecimal.class);
        query.addScalar("status", String.class);
        query.addScalar("source_type", String.class);
        query.addScalar("transaction_attribution_id", Long.class);
        query.addScalar("attributed_amount", BigDecimal.class);
        query.addScalar("attribution_date", LocalDate.class);
        query.addScalar("inclusion_status", String.class);
        query.addScalar("agent_id", Long.class);
        query.addScalar("agent_name", String.class);
        query.addScalar("agent_code", String.class);
        query.addScalar("transaction_attributed_total", BigDecimal.class);

        // 3. 지급 건별 묶기와 차액 계산에 필요한 귀속행 DTO 반환
        return query.setTupleTransformer((row, aliases) -> toAttributionRow(row)).getResultList();
    }

    /**
     * 설명 : 계약의 대사 결과를 생성시각·결과 ID 내림차순으로 조회한다.
     *
     * @param contractId 보험계약 ID
     * @return 최신순 대사 결과. 대사 결과가 없으면 빈 목록
     */
    public List<ContractReconciliationRow> findReconciliationResultsByContractId(Long contractId) {
        // 1. 해당 계약의 대사 결과를 최신순으로 조회
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT reconciliation_result_id, match_group_key, result_type,
                       expected_total_amount, actual_total_amount, difference_amount,
                       primary_reason_code, installment_no, commission_item_id, created_at
                  FROM fgc.reconciliation_result
                 WHERE contract_id = :contractId
                 ORDER BY created_at DESC, reconciliation_result_id DESC
                """).unwrap(NativeQuery.class);
        query.setParameter("contractId", contractId);

        // 2. 금액과 생성시각을 포함한 조회 결과의 Java 타입 지정
        query.addScalar("reconciliation_result_id", Long.class);
        query.addScalar("match_group_key", String.class);
        query.addScalar("result_type", String.class);
        query.addScalar("expected_total_amount", BigDecimal.class);
        query.addScalar("actual_total_amount", BigDecimal.class);
        query.addScalar("difference_amount", BigDecimal.class);
        query.addScalar("primary_reason_code", String.class);
        query.addScalar("installment_no", Integer.class);
        query.addScalar("commission_item_id", Long.class);
        query.addScalar("created_at", OffsetDateTime.class);

        // 3. 코드·금액·시각과 선택 항목의 null 값을 보존하여 DTO 반환
        return query.setTupleTransformer((row, aliases) -> toReconciliationRow(row)).getResultList();
    }

    private ContractTransactionAttributionRow toAttributionRow(Object[] row) {
        ContractTransactionAttributionRow result = new ContractTransactionAttributionRow();
        result.setCommissionTransactionId((Long) row[0]);
        result.setSettlementMonth((LocalDate) row[1]);
        result.setPaymentStage((String) row[2]);
        result.setAmount((BigDecimal) row[3]);
        result.setStatus((String) row[4]);
        result.setSourceType((String) row[5]);
        result.setTransactionAttributionId((Long) row[6]);
        result.setAttributedAmount((BigDecimal) row[7]);
        result.setAttributionDate((LocalDate) row[8]);
        result.setInclusionStatus((String) row[9]);
        result.setAgentId((Long) row[10]);
        result.setAgentName((String) row[11]);
        result.setAgentCode((String) row[12]);
        result.setTransactionAttributedTotal((BigDecimal) row[13]);
        return result;
    }

    private ContractReconciliationRow toReconciliationRow(Object[] row) {
        ContractReconciliationRow result = new ContractReconciliationRow();
        result.setReconciliationResultId((Long) row[0]);
        result.setMatchGroupKey((String) row[1]);
        result.setResultType((String) row[2]);
        result.setExpectedTotalAmount((BigDecimal) row[3]);
        result.setActualTotalAmount((BigDecimal) row[4]);
        result.setDifferenceAmount((BigDecimal) row[5]);
        result.setPrimaryReasonCode((String) row[6]);
        result.setInstallmentNo((Integer) row[7]);
        result.setCommissionItemId((Long) row[8]);
        result.setCreatedAt((OffsetDateTime) row[9]);
        return result;
    }
}
