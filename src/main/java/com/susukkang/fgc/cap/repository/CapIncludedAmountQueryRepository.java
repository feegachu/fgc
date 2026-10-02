package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.dto.CapIncludedAmountSummary;
import com.susukkang.fgc.common.code.PaymentStage;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 설명 : 계약·설계사·지급단계별 초년도 한도 산입액을 집계한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-10-02
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CapIncludedAmountQueryRepository {

    // 행별 원 단위 반올림과 PostgreSQL의 달력 기준 1년 계산을 기존 집계와 동일하게 유지한다.
    private static final String BASE_SQL = """
            SELECT ta.contract_id,
                   ta.agent_id,
                   ct.payment_stage,
                   COALESCE(SUM(
                       CASE WHEN ct.cashflow_type = 'DEDUCTION'
                            THEN -ROUND(ta.attributed_amount, 0)
                            ELSE ROUND(ta.attributed_amount, 0)
                       END
                   ), 0) AS included_amount
              FROM fgc.transaction_attribution ta
              JOIN fgc.commission_transaction ct
                ON ct.commission_transaction_id = ta.commission_transaction_id
              JOIN fgc.insurance_contract c
                ON c.contract_id = ta.contract_id
             WHERE ta.contract_id = :contractId
               AND ct.payment_stage = :paymentStage
               AND ta.inclusion_status_snapshot = 'INCLUDED'
               AND ta.attribution_date >= c.contract_date
               AND ta.attribution_date < c.contract_date + INTERVAL '1 year'
            """;

    private static final String GROUP_BY = """
             GROUP BY ta.contract_id, ta.agent_id, ct.payment_stage
            """;

    private final EntityManager entityManager;

    /** 기존 확정 지급과 현재 지급 건의 산입액을 합산하며, 현재 건이 이미 확정됐어도 한 번만 포함한다. */
    public List<CapIncludedAmountSummary> sumIncludedAmountByContractAndAgent(
            Long contractId, Long transactionId, PaymentStage paymentStage
    ) {
        NativeQuery<CapIncludedAmountSummary> query = createSummaryQuery("""
                   AND (ct.status = 'CONFIRMED' OR ct.commission_transaction_id = :transactionId)
                """, contractId, paymentStage);
        query.setParameter("transactionId", transactionId, Long.class);
        return query.getResultList();
    }

    /** 월 검증 시 확정 지급의 산입액만 재합산한다. */
    public List<CapIncludedAmountSummary> sumConfirmedIncludedAmountByContractAndAgent(
            Long contractId, PaymentStage paymentStage
    ) {
        return createSummaryQuery("""
                   AND ct.status = 'CONFIRMED'
                """, contractId, paymentStage).getResultList();
    }

    private NativeQuery<CapIncludedAmountSummary> createSummaryQuery(
            String statusCondition, Long contractId, PaymentStage paymentStage
    ) {
        NativeQuery<?> query = entityManager.createNativeQuery(BASE_SQL + statusCondition + GROUP_BY)
                .unwrap(NativeQuery.class);
        query.setParameter("contractId", contractId, Long.class);
        query.setParameter("paymentStage", paymentStage == null ? null : paymentStage.name(), String.class);
        query.addScalar("contract_id", Long.class);
        query.addScalar("agent_id", Long.class);
        query.addScalar("payment_stage", String.class);
        query.addScalar("included_amount", BigDecimal.class);
        return query.setTupleTransformer((tuple, aliases) -> new CapIncludedAmountSummary(
                (Long) tuple[0],
                (Long) tuple[1],
                PaymentStage.valueOf((String) tuple[2]),
                (BigDecimal) tuple[3]
        ));
    }
}
