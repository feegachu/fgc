package com.susukkang.fgc.arbitrage.repository;

import com.susukkang.fgc.arbitrage.dto.*;
import com.susukkang.fgc.common.code.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * PostgreSQL DISTINCT ON, LATERAL, 행별 ROUND 집계 및 UPSERT RETURNING의 기존 의미를 유지한다.
 * 네이티브 변경 전 flush, 변경 후 clear하여 공유 JPA 저장과 조회 가시성을 보장한다.
 */
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ArbitrageCheckQueriesImpl implements ArbitrageCheckQueries {
    private final EntityManager entityManager;

    private static final String SELECT_BY_CONDITION = """
            SELECT
            ac.arbitrage_check_id,
            ac.contract_id,
            c.contract_no,
            ac.payment_stage,
            ac.as_of_date,
            ac.contract_month_no,
            ac.cumulative_paid_premium,
            ac.paid_commission_amount,
            ac.planned_commission_amount,
            ac.included_surrender_value_amount,
            ac.net_difference_amount,
            ac.refund_addition_applied_yn,
            ac.surrender_value_source_type,
            ac.result_status,
            ac.calculation_snapshot ->> 'decisionReason' AS decision_reason
            FROM
            (SELECT DISTINCT ON (contract_id, payment_stage, as_of_date) *
            FROM fgc.arbitrage_check
            ORDER BY contract_id, payment_stage, as_of_date, arbitrage_check_id DESC) ac
            JOIN fgc.insurance_contract c
            ON
            c.contract_id = ac.contract_id
            __FILTER__
            ORDER BY
            ac.arbitrage_check_id DESC
            """;

    private static final String ARBITRAGE_CHECK_SUMMARY = """
            SELECT
            COUNT(*) FILTER (WHERE ac.result_status = 'CLEAR') AS clear_count,
            COUNT(*) FILTER (WHERE ac.result_status = 'CANDIDATE') AS candidate_count,
            COUNT(*) FILTER (WHERE ac.result_status = 'REVIEW_REQUIRED') AS review_required_count
            FROM
            (SELECT DISTINCT ON (contract_id, payment_stage, as_of_date) *
            FROM fgc.arbitrage_check
            ORDER BY contract_id, payment_stage, as_of_date, arbitrage_check_id DESC) ac
            JOIN fgc.insurance_contract c
            ON
            c.contract_id = ac.contract_id
            __FILTER__
            """;

    private static final String COUNT_BY_CONDITION = """
            SELECT COUNT(*)
            FROM (SELECT DISTINCT ON (contract_id, payment_stage, as_of_date) *
            FROM fgc.arbitrage_check
            ORDER BY contract_id, payment_stage, as_of_date, arbitrage_check_id DESC) ac
            JOIN fgc.insurance_contract c
            ON c.contract_id = ac.contract_id
            __FILTER__
            """;

    private static final String SELECT_CALCULATION_SOURCE = """
            SELECT
            c.contract_id,
            c.contract_date,
            c.insurer_id,
            p.product_id,
            c.product_offering_id,
            c.payment_term_months,
            po.channel_code,
            po.standard_deduction_80_yn,
            c.standard_surrender_deduction_amount,
            fs.as_of_date AS snapshot_as_of_date,
            fs.contract_month_no,
            fs.cumulative_paid_premium,
            fs.surrender_value,
            fs.surrender_value_type,
            fs.refund_rate_table_id AS snapshot_refund_rate_table_id
            FROM fgc.insurance_contract c
            JOIN fgc.product_offering po
            ON
            po.product_offering_id = c.product_offering_id
            JOIN fgc.product p
            ON
            p.product_id = po.product_id
            LEFT JOIN LATERAL (
            SELECT
            cfs.as_of_date,
            cfs.contract_month_no,
            cfs.cumulative_paid_premium,
            cfs.surrender_value,
            cfs.surrender_value_type,
            cfs.refund_rate_table_id
            FROM fgc.contract_financial_snapshot cfs
            WHERE
            cfs.contract_id = c.contract_id
            AND
            cfs.as_of_date  <=  :asOfDate
            ORDER BY
            cfs.as_of_date DESC,
            CASE WHEN cfs.surrender_value_type = 'ACTUAL' THEN 0 ELSE 1 END,
            cfs.contract_financial_snapshot_id DESC
            LIMIT 1
            ) fs ON true
            WHERE
            c.contract_id = :contractId
            """;

    private static final String SUM_CONFIRMED_COMMISSION_AMOUNT = """
            SELECT
            COALESCE(SUM(CASE WHEN ct.cashflow_type = 'PAYMENT'
            THEN ROUND(ta.attributed_amount, 0) ELSE 0 END), 0) AS confirmed_payment_amount,
            COALESCE(SUM(CASE WHEN ct.cashflow_type = 'DEDUCTION'
            THEN ROUND(ta.attributed_amount, 0) ELSE 0 END), 0) AS confirmed_deduction_amount,
            COALESCE(SUM(
            CASE
            WHEN ct.cashflow_type = 'DEDUCTION'
            THEN -ROUND(ta.attributed_amount, 0)
            ELSE ROUND(ta.attributed_amount, 0)
            END
            ), 0) AS paid_commission_amount
            FROM fgc.transaction_attribution ta
            JOIN fgc.commission_transaction ct
            ON
            ct.commission_transaction_id = ta.commission_transaction_id
            WHERE
            ta.contract_id = :contractId
            AND
            ct.payment_stage = :paymentStage
            AND
            ct.status = 'CONFIRMED'
            AND
            ta.attribution_date  <=  :asOfDate
            """;

    private static final String SUM_PLANNED_COMMISSION_AMOUNT = """
            SELECT
            COALESCE(SUM(ROUND(sl.expected_amount, 0)), 0)
            FROM fgc.schedule_header sh
            JOIN fgc.schedule_line sl
            ON
            sl.schedule_header_id = sh.schedule_header_id
            WHERE
            sh.contract_id = :contractId
            AND
            sh.payment_stage = :paymentStage
            AND
            sh.active_yn = true
            AND
            sh.schedule_purpose = 'OPERATIONAL'
            AND
            sl.line_status = 'PLANNED'
            """;

    // 공용 정책 엔티티를 재사용하는 일반 조인은 JPQL로 처리한다.
    private static final String SELECT_REFUND_RATE_CANDIDATES = """
            SELECT table.refundRateTableId, line.refundRatePct
              FROM RefundRateTable table
              JOIN RefundRateLine line ON line.refundRateTableId = table.refundRateTableId
             WHERE table.insurerId = :insurerId
               AND table.productId = :productId
               AND table.paymentTermMonths = :paymentTermMonths
               AND table.channelCode = :channelCode
               AND (table.productOfferingId IS NULL OR table.productOfferingId = :productOfferingId)
               AND table.standardDeduction80Yn = true
               AND table.effectiveFrom <= :contractDate
               AND (table.effectiveTo IS NULL OR table.effectiveTo >= :contractDate)
               AND line.contractMonthNo = :contractMonthNo
             ORDER BY CASE WHEN table.productOfferingId IS NOT NULL THEN 0 ELSE 1 END,
                      table.effectiveFrom DESC, table.refundRateTableId DESC
            """;

    private static final String INSERT_ARBITRAGE_CHECK = """
            INSERT INTO fgc.arbitrage_check (
            validation_run_id,
            contract_id,
            payment_stage,
            as_of_date,
            contract_month_no,
            cumulative_paid_premium,
            paid_commission_amount,
            planned_commission_amount,
            included_surrender_value_amount,
            refund_addition_applied_yn,
            surrender_value_source_type,
            net_difference_amount,
            refund_rate_table_id,
            standard_deduction_80_yn,
            result_status,
            calculation_snapshot
            ) VALUES (
            :validationRunId,
            :contractId,
            :paymentStage,
            :asOfDate,
            :contractMonthNo,
            :cumulativePaidPremium,
            :paidCommissionAmount,
            :plannedCommissionAmount,
            :includedSurrenderValueAmount,
            :refundAdditionAppliedYn,
            :surrenderValueSourceType,
            :netDifferenceAmount,
            :refundRateTableId,
            :standardDeduction80Yn,
            :resultStatus,
            CAST(:calculationSnapshot AS jsonb)
            )
            ON CONFLICT ON CONSTRAINT uq_arbitrage_check
            DO UPDATE SET
            contract_month_no = EXCLUDED.contract_month_no,
            cumulative_paid_premium = EXCLUDED.cumulative_paid_premium,
            paid_commission_amount = EXCLUDED.paid_commission_amount,
            planned_commission_amount = EXCLUDED.planned_commission_amount,
            included_surrender_value_amount = EXCLUDED.included_surrender_value_amount,
            refund_addition_applied_yn = EXCLUDED.refund_addition_applied_yn,
            surrender_value_source_type = EXCLUDED.surrender_value_source_type,
            net_difference_amount = EXCLUDED.net_difference_amount,
            refund_rate_table_id = EXCLUDED.refund_rate_table_id,
            standard_deduction_80_yn = EXCLUDED.standard_deduction_80_yn,
            result_status = EXCLUDED.result_status,
            calculation_snapshot = EXCLUDED.calculation_snapshot
            RETURNING arbitrage_check_id
            """;

    private static final String SELECT_BY_CONTRACT_ID = """
            SELECT
            ac.arbitrage_check_id,
            ac.contract_id,
            c.contract_no,
            ac.payment_stage,
            ac.as_of_date,
            ac.contract_month_no,
            ac.cumulative_paid_premium,
            ac.paid_commission_amount,
            ac.planned_commission_amount,
            ac.included_surrender_value_amount,
            ac.net_difference_amount,
            ac.refund_addition_applied_yn,
            ac.surrender_value_source_type,
            ac.result_status,
            ac.calculation_snapshot ->> 'decisionReason' AS decision_reason
            FROM
            (SELECT DISTINCT ON (contract_id, payment_stage, as_of_date) *
            FROM fgc.arbitrage_check
            ORDER BY contract_id, payment_stage, as_of_date, arbitrage_check_id DESC) ac
            JOIN fgc.insurance_contract c
            ON
            c.contract_id = ac.contract_id
            WHERE
            ac.contract_id = :contractId
            AND
            ac.payment_stage = :paymentStage
            ORDER BY
            ac.as_of_date ASC,
            ac.arbitrage_check_id ASC
            """;

    private NativeQuery<?> query(String sql) {
        return entityManager.createNativeQuery(sql).unwrap(NativeQuery.class);
    }

    private NativeQuery<?> search(String sql, ArbitrageCheckSearchCondition condition) {
        StringBuilder filter = new StringBuilder("WHERE true");
        if (condition.getMonth() != null) filter.append(" AND ac.as_of_date >= :monthStart AND ac.as_of_date < :nextMonthStart");
        if (condition.getStatus() != null) filter.append(" AND ac.result_status = :status");
        if (condition.getStage() != null) filter.append(" AND ac.payment_stage = :stage");
        if (condition.getInsurerId() != null) filter.append(" AND c.insurer_id = :insurerId");
        if (condition.getContractNo() != null && !condition.getContractNo().isEmpty()) filter.append(" AND c.contract_no = :contractNo");
        NativeQuery<?> q = query(sql.replace("__FILTER__", filter));
        if (condition.getMonth() != null) {
            q.setParameter("monthStart", condition.getMonthStart(), LocalDate.class);
            q.setParameter("nextMonthStart", condition.getNextMonthStart(), LocalDate.class);
        }
        if (condition.getStatus() != null) q.setParameter("status", condition.getStatus().name(), String.class);
        if (condition.getStage() != null) q.setParameter("stage", condition.getStage().name(), String.class);
        if (condition.getInsurerId() != null) q.setParameter("insurerId", condition.getInsurerId(), Long.class);
        if (condition.getContractNo() != null && !condition.getContractNo().isEmpty()) q.setParameter("contractNo", condition.getContractNo(), String.class);
        return q;
    }

    @Override
    public List<ArbitrageCheckView> selectByCondition(ArbitrageCheckSearchCondition condition, int offset, int size) {
        return search(SELECT_BY_CONDITION, condition).setFirstResult(offset).setMaxResults(size)
                .setTupleTransformer((tuple, aliases) -> view(tuple)).getResultList();
    }

    @Override
    public ArbitrageCheckSummary arbitrageCheckSummary(ArbitrageCheckSearchCondition condition) {
        Object[] row = (Object[]) search(ARBITRAGE_CHECK_SUMMARY, condition).getSingleResult();
        return new ArbitrageCheckSummary(((Number) row[0]).longValue(), ((Number) row[1]).longValue(), ((Number) row[2]).longValue());
    }

    @Override
    public long countByCondition(ArbitrageCheckSearchCondition condition) {
        return ((Number) search(COUNT_BY_CONDITION, condition).getSingleResult()).longValue();
    }

    @Override
    public List<ArbitrageCheckView> selectByContractId(Long contractId, PaymentStage paymentStage) {
        return query(SELECT_BY_CONTRACT_ID).setParameter("contractId", contractId, Long.class)
                .setParameter("paymentStage", paymentStage.name(), String.class)
                .setTupleTransformer((tuple, aliases) -> view(tuple)).getResultList();
    }

    @Override
    public ArbitrageCalculationSource selectCalculationSource(Long contractId, LocalDate asOfDate) {
        List<ArbitrageCalculationSource> rows = query(SELECT_CALCULATION_SOURCE)
                .setParameter("contractId", contractId, Long.class).setParameter("asOfDate", asOfDate, LocalDate.class)
                .setTupleTransformer((tuple, aliases) -> source(tuple)).getResultList();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public ConfirmedCommissionSummary sumConfirmedCommissionAmount(Long contractId, PaymentStage paymentStage, LocalDate asOfDate) {
        Object[] row = (Object[]) query(SUM_CONFIRMED_COMMISSION_AMOUNT)
                .setParameter("contractId", contractId, Long.class).setParameter("paymentStage", paymentStage.name(), String.class)
                .setParameter("asOfDate", asOfDate, LocalDate.class).getSingleResult();
        ConfirmedCommissionSummary result = new ConfirmedCommissionSummary();
        result.setConfirmedPaymentAmount((BigDecimal) row[0]);
        result.setConfirmedDeductionAmount((BigDecimal) row[1]);
        result.setPaidCommissionAmount((BigDecimal) row[2]);
        return result;
    }

    @Override
    public BigDecimal sumPlannedCommissionAmount(Long contractId, PaymentStage paymentStage) {
        return (BigDecimal) query(SUM_PLANNED_COMMISSION_AMOUNT).setParameter("contractId", contractId, Long.class)
                .setParameter("paymentStage", paymentStage.name(), String.class).getSingleResult();
    }

    @Override
    public List<ArbitrageRefundRateCandidate> selectRefundRateCandidates(ArbitrageCalculationSource source, LocalDate asOfDate) {
        return entityManager.createQuery(SELECT_REFUND_RATE_CANDIDATES, Object[].class)
                .setParameter("insurerId", source.getInsurerId())
                .setParameter("productId", source.getProductId())
                .setParameter("paymentTermMonths", source.getPaymentTermMonths())
                .setParameter("channelCode", source.getChannelCode())
                .setParameter("productOfferingId", source.getProductOfferingId())
                .setParameter("contractDate", source.getContractDate())
                .setParameter("contractMonthNo", source.getContractMonthNo())
                .getResultList().stream().map(tuple -> {
                    ArbitrageRefundRateCandidate result = new ArbitrageRefundRateCandidate();
                    result.setRefundRateTableId((Long) tuple[0]);
                    result.setRefundRatePct((BigDecimal) tuple[1]);
                    return result;
                }).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int insertArbitrageCheck(ArbitrageCheckInsertDTO row) {
        entityManager.flush();
        NativeQuery<?> q = query(INSERT_ARBITRAGE_CHECK);
        q.setParameter("validationRunId", row.getValidationRunId(), Long.class);
        q.setParameter("contractId", row.getContractId(), Long.class);
        q.setParameter("paymentStage", row.getPaymentStage() == null ? null : row.getPaymentStage().name(), String.class);
        q.setParameter("asOfDate", row.getAsOfDate(), LocalDate.class);
        q.setParameter("contractMonthNo", row.getContractMonthNo(), Integer.class);
        q.setParameter("cumulativePaidPremium", row.getCumulativePaidPremium(), BigDecimal.class);
        q.setParameter("paidCommissionAmount", row.getPaidCommissionAmount(), BigDecimal.class);
        q.setParameter("plannedCommissionAmount", row.getPlannedCommissionAmount(), BigDecimal.class);
        q.setParameter("includedSurrenderValueAmount", row.getIncludedSurrenderValueAmount(), BigDecimal.class);
        q.setParameter("refundAdditionAppliedYn", row.getRefundAdditionAppliedYn(), Boolean.class);
        q.setParameter("surrenderValueSourceType", row.getSurrenderValueSourceType() == null ? null : row.getSurrenderValueSourceType().name(), String.class);
        q.setParameter("netDifferenceAmount", row.getNetDifferenceAmount(), BigDecimal.class);
        q.setParameter("refundRateTableId", row.getRefundRateTableId(), Long.class);
        q.setParameter("standardDeduction80Yn", row.getStandardDeduction80Yn(), Boolean.class);
        q.setParameter("resultStatus", row.getResultStatus() == null ? null : row.getResultStatus().name(), String.class);
        q.setParameter("calculationSnapshot", row.getCalculationSnapshot(), String.class);
        row.setArbitrageCheckId(((Number) q.getSingleResult()).longValue());
        entityManager.clear();
        return 1;
    }

    private static ArbitrageCheckView view(Object[] row) {
        ArbitrageCheckView result = new ArbitrageCheckView();
        result.setArbitrageCheckId((Long) row[0]);
        result.setContractId((Long) row[1]);
        result.setContractNo((String) row[2]);
        result.setPaymentStage(row[3] == null ? null : PaymentStage.valueOf((String) row[3]));
        result.setAsOfDate(row[4] == null ? null : ((java.sql.Date) row[4]).toLocalDate());
        result.setContractMonthNo((Integer) row[5]);
        result.setCumulativePaidPremium((BigDecimal) row[6]);
        result.setPaidCommissionAmount((BigDecimal) row[7]);
        result.setPlannedCommissionAmount((BigDecimal) row[8]);
        result.setIncludedSurrenderValueAmount((BigDecimal) row[9]);
        result.setNetDifferenceAmount((BigDecimal) row[10]);
        result.setRefundAdditionAppliedYn((Boolean) row[11]);
        result.setSurrenderValueSourceType(row[12] == null ? null : SurrenderValueSourceType.valueOf((String) row[12]));
        result.setResultStatus(row[13] == null ? null : ArbitrageCheckStatus.valueOf((String) row[13]));
        result.setDecisionReason((String) row[14]);
        return result;
    }

    private static ArbitrageCalculationSource source(Object[] row) {
        ArbitrageCalculationSource result = new ArbitrageCalculationSource();
        result.setContractId((Long) row[0]);
        result.setContractDate(row[1] == null ? null : ((java.sql.Date) row[1]).toLocalDate());
        result.setInsurerId((Long) row[2]);
        result.setProductId((Long) row[3]);
        result.setProductOfferingId((Long) row[4]);
        result.setPaymentTermMonths((Integer) row[5]);
        result.setChannelCode((String) row[6]);
        result.setStandardDeduction80Yn((Boolean) row[7]);
        result.setStandardSurrenderDeductionAmount((BigDecimal) row[8]);
        result.setSnapshotAsOfDate(row[9] == null ? null : ((java.sql.Date) row[9]).toLocalDate());
        result.setContractMonthNo((Integer) row[10]);
        result.setCumulativePaidPremium((BigDecimal) row[11]);
        result.setSurrenderValue((BigDecimal) row[12]);
        result.setSurrenderValueType((String) row[13]);
        result.setSnapshotRefundRateTableId((Long) row[14]);
        return result;
    }
}
