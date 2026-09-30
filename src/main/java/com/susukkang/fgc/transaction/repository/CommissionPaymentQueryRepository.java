package com.susukkang.fgc.transaction.repository;

import com.susukkang.fgc.base.entity.Agent;
import com.susukkang.fgc.base.repository.AgentRepository;
import com.susukkang.fgc.common.code.*;
import com.susukkang.fgc.transaction.domain.*;
import com.susukkang.fgc.transaction.dto.CommissionPaymentListResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentSearchCondition;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.susukkang.fgc.transaction.repository.CommissionPaymentSql.*;

/**
 * 설명 : 지급 조회 DTO와 확정 시점의 판정 입력 조회. 단순 조회는 공유 JPA·JPQL을 사용한다.
 * PostgreSQL 집계·JSON·선택적 잠금은 기존 SQL을 유지하며 지급 → 계약 ID 순 잠금을 보존한다.
 *
 * @author hjKang
 * @since 2026-09-30
 * @version 1.0
 */
@Repository
@RequiredArgsConstructor
public class CommissionPaymentQueryRepository {
    private final EntityManager entityManager;
    private final AgentRepository agentRepository;

    public boolean existsAgent(Long agentId) {
        return agentId != null && agentRepository.existsById(agentId);
    }

    public LocalDate findAgentAppointmentDate(Long agentId) {
        return agentId == null ? null : agentRepository.findById(agentId).map(Agent::getAppointmentDate).orElse(null);
    }

    public AgentRankCode findAgentRankCode(Long agentId) {
        return agentId == null ? null : agentRepository.findById(agentId).map(Agent::getRankCode).orElse(null);
    }

    public boolean existsEligibleNewcomerSupportAgent(Long agentId, LocalDate asOfDate) {
        return agentId != null && asOfDate != null && agentRepository.findById(agentId)
                .filter(a -> a.isActiveYn() && a.isNewcomerSupportEligibleYn()
                        && Boolean.FALSE.equals(a.getPriorThreeYearExperienceYn())
                        && a.getNewcomerSupportEndDate() != null
                        && !a.getNewcomerSupportEndDate().isBefore(asOfDate))
                .isPresent();
    }

    public ContractReference findContract(Long contractId) {
        return entityManager.createQuery("""
                SELECT new com.susukkang.fgc.transaction.domain.ContractReference(
                    c.contractId, c.agentId, c.organizationId, c.contractDate)
                  FROM InsuranceContract c WHERE c.contractId = :contractId
                """, ContractReference.class)
                .setParameter("contractId", contractId).getResultStream().findFirst().orElse(null);
    }

    public int countContractsBeforeMonth(Long agentId, LocalDate monthStart) {
        return entityManager.createQuery("""
                SELECT COUNT(c) FROM InsuranceContract c
                 WHERE c.agentId = :agentId AND c.contractDate < :monthStart
                """, Long.class)
                .setParameter("agentId", agentId).setParameter("monthStart", monthStart)
                .getSingleResult().intValue();
    }

    public CommissionItemReference findCommissionItem(Long commissionItemId, LocalDate asOf) {
        return entityManager.createQuery("""
                SELECT new com.susukkang.fgc.transaction.domain.CommissionItemReference(
                    i.commissionItemId, i.itemCode, i.cashflowType)
                  FROM CommissionItem i
                 WHERE i.commissionItemId = :commissionItemId AND i.activeYn = true
                   AND i.effectiveFrom <= :asOf AND (i.effectiveTo IS NULL OR i.effectiveTo >= :asOf)
                """, CommissionItemReference.class)
                .setParameter("commissionItemId", commissionItemId).setParameter("asOf", asOf)
                .getResultStream().findFirst().orElse(null);
    }

    public boolean existsPolicyVersion(Long policyVersionId) {
        return entityManager.createQuery("""
                SELECT COUNT(p) FROM PolicyVersion p
                 WHERE p.policyVersionId = :policyVersionId AND p.status IN :statuses
                """, Long.class)
                .setParameter("policyVersionId", policyVersionId)
                .setParameter("statuses", List.of(PolicyStatus.APPROVED, PolicyStatus.ACTIVE))
                .getSingleResult() > 0;
    }

    public List<CommissionPaymentListResponse> selectByCondition(CommissionPaymentSearchCondition condition, int size, long offset) {
        SearchFilter filter = searchFilter(condition);
        NativeQuery<?> query = nativeQuery(SELECT_BY_CONDITION.replace("__FILTER__", filter.sql()));
        filter.parameters().forEach(query::setParameter);
        query.setParameter("size", size);
        query.setParameter("offset", offset);
        query.addScalar("commission_transaction_id", Long.class);
        query.addScalar("payment_stage", String.class);
        query.addScalar("payment_stage_label", String.class);
        query.addScalar("source_type", String.class);
        query.addScalar("source_type_label", String.class);
        query.addScalar("source_business_key", String.class);
        query.addScalar("settlement_month", LocalDate.class);
        query.addScalar("recipient_agent_id", Long.class);
        query.addScalar("recipient_agent_name", String.class);
        query.addScalar("commission_item_id", Long.class);
        query.addScalar("commission_item_code", String.class);
        query.addScalar("commission_item_name", String.class);
        query.addScalar("amount", BigDecimal.class);
        query.addScalar("cashflow_type", String.class);
        query.addScalar("cashflow_type_label", String.class);
        query.addScalar("status", String.class);
        query.addScalar("status_label", String.class);
        query.addScalar("attribution_count", Integer.class);
        query.addScalar("difference_amount", BigDecimal.class);
        return query.setTupleTransformer((row, aliases) -> new CommissionPaymentListResponse(
                (Long) row[0],
                enumValue(PaymentStage.class, row[1]),
                (String) row[2],
                (String) row[3],
                (String) row[4],
                (String) row[5],
                (LocalDate) row[6],
                (Long) row[7],
                (String) row[8],
                (Long) row[9],
                (String) row[10],
                (String) row[11],
                (BigDecimal) row[12],
                (String) row[13],
                (String) row[14],
                enumValue(CommissionPaymentStatus.class, row[15]),
                (String) row[16],
                (Integer) row[17],
                (BigDecimal) row[18]
        )).getResultList();
    }

    public long countByCondition(CommissionPaymentSearchCondition condition) {
        SearchFilter filter = searchFilter(condition);
        NativeQuery<?> query = nativeQuery(COUNT_BY_CONDITION.replace("__FILTER__", filter.sql()));
        filter.parameters().forEach(query::setParameter);
        return query.setTupleTransformer((row, aliases) -> ((Number) row[0]).longValue())
                .getResultList().stream().findFirst().orElse(0L);
    }

    public Long findAllocationPolicyId(Long policyVersionId, String allocationBasis) {
        NativeQuery<?> query = nativeQuery(FIND_ALLOCATION_POLICY_ID);
        query.setParameter("policyVersionId", policyVersionId);
        query.setParameter("allocationBasis", allocationBasis);
        return query.setTupleTransformer((row, aliases) -> ((Number) row[0]).longValue())
                .getResultList().stream().findFirst().orElse(null);
    }

    public List<Long> findOperationalScheduleLineIds(Long contractId, PaymentStage paymentStage, Long commissionItemId, Long beneficiaryAgentId, LocalDate attributionMonthStart, LocalDate nextAttributionMonthStart) {
        NativeQuery<?> query = nativeQuery(FIND_OPERATIONAL_SCHEDULE_LINE_IDS);
        query.setParameter("contractId", contractId);
        query.setParameter("paymentStage", paymentStage == null ? null : paymentStage.name());
        query.setParameter("commissionItemId", commissionItemId);
        query.setParameter("beneficiaryAgentId", beneficiaryAgentId);
        query.setParameter("attributionMonthStart", attributionMonthStart);
        query.setParameter("nextAttributionMonthStart", nextAttributionMonthStart);
        return query.setTupleTransformer((row, aliases) -> ((Number) row[0]).longValue())
                .getResultList();
    }

    public List<Integer> findOperationalScheduleInstallmentNos(Long contractId, PaymentStage paymentStage, Long commissionItemId, Long beneficiaryAgentId, LocalDate attributionMonthStart, LocalDate nextAttributionMonthStart) {
        NativeQuery<?> query = nativeQuery(FIND_OPERATIONAL_SCHEDULE_INSTALLMENT_NOS);
        query.setParameter("contractId", contractId);
        query.setParameter("paymentStage", paymentStage == null ? null : paymentStage.name());
        query.setParameter("commissionItemId", commissionItemId);
        query.setParameter("beneficiaryAgentId", beneficiaryAgentId);
        query.setParameter("attributionMonthStart", attributionMonthStart);
        query.setParameter("nextAttributionMonthStart", nextAttributionMonthStart);
        return query.setTupleTransformer((row, aliases) -> row[0] == null ? null : ((Number) row[0]).intValue())
                .getResultList();
    }

    public CommissionPaymentRow findById(Long paymentId) {
        NativeQuery<?> query = nativeQuery(FIND_BY_ID);
        query.setParameter("paymentId", paymentId);
        query.addScalar("payment_id", Long.class);
        query.addScalar("source_type", String.class);
        query.addScalar("source_business_key", String.class);
        query.addScalar("contract_id", Long.class);
        query.addScalar("agent_id", Long.class);
        query.addScalar("commission_item_id", Long.class);
        query.addScalar("commission_item_code", String.class);
        query.addScalar("commission_item_name", String.class);
        query.addScalar("amount", BigDecimal.class);
        query.addScalar("settlement_month", LocalDate.class);
        query.addScalar("cashflow_type", String.class);
        query.addScalar("scheduled_payment_date", LocalDate.class);
        query.addScalar("payment_stage", String.class);
        query.addScalar("status", String.class);
        query.addScalar("allocation_policy_version", Long.class);
        query.addScalar("evidence_ref", String.class);
        query.addScalar("note", String.class);
        query.addScalar("created_at", OffsetDateTime.class);
        query.addScalar("updated_at", OffsetDateTime.class);
        return query.setTupleTransformer((row, aliases) -> new CommissionPaymentRow(
                (Long) row[0],
                (String) row[1],
                (String) row[2],
                (Long) row[3],
                (Long) row[4],
                (Long) row[5],
                (String) row[6],
                (String) row[7],
                (BigDecimal) row[8],
                (LocalDate) row[9],
                (String) row[10],
                (LocalDate) row[11],
                enumValue(PaymentStage.class, row[12]),
                enumValue(CommissionPaymentStatus.class, row[13]),
                (Long) row[14],
                (String) row[15],
                (String) row[16],
                (OffsetDateTime) row[17],
                (OffsetDateTime) row[18]
        )).getResultList().stream().findFirst().orElse(null);
    }

    public List<CommissionPaymentAttributionRow> findAttributions(Long paymentId) {
        NativeQuery<?> query = nativeQuery(FIND_ATTRIBUTIONS);
        query.setParameter("paymentId", paymentId);
        query.addScalar("attribution_sequence", Integer.class);
        query.addScalar("contract_id", Long.class);
        query.addScalar("attribution_date", LocalDate.class);
        query.addScalar("attribution_month", LocalDate.class);
        query.addScalar("amount", BigDecimal.class);
        query.addScalar("inclusion_decision_status", String.class);
        query.addScalar("exclusion_type", String.class);
        query.addScalar("inclusion_decision_reason", String.class);
        query.addScalar("allocation_basis", String.class);
        query.addScalar("evidence_ref", String.class);
        query.addScalar("attribution_method", String.class);
        return query.setTupleTransformer((row, aliases) -> new CommissionPaymentAttributionRow(
                (Integer) row[0],
                (Long) row[1],
                (LocalDate) row[2],
                (LocalDate) row[3],
                (BigDecimal) row[4],
                enumValue(InclusionDecisionStatus.class, row[5]),
                enumValue(ExclusionType.class, row[6]),
                (String) row[7],
                (String) row[8],
                (String) row[9],
                enumValue(AttributionMethod.class, row[10])
        )).getResultList();
    }

    public List<ConfirmationData> findConfirmationDataForUpdate(Long paymentId) {
        NativeQuery<?> query = nativeQuery(FIND_CONFIRMATION_DATA_FOR_UPDATE);
        query.setParameter("paymentId", paymentId);
        query.addScalar("payment_id", Long.class);
        query.addScalar("status", String.class);
        query.addScalar("amount", BigDecimal.class);
        query.addScalar("attributed_amount", BigDecimal.class);
        query.addScalar("total_attributed_amount", BigDecimal.class);
        query.addScalar("confirm_idempotency_key", String.class);
        query.addScalar("attribution_date", LocalDate.class);
        query.addScalar("attribution_month", LocalDate.class);
        query.addScalar("transaction_attribution_id", Long.class);
        query.addScalar("contract_id", Long.class);
        query.addScalar("agent_id", Long.class);
        query.addScalar("payment_stage", String.class);
        query.addScalar("commission_item_id", Long.class);
        query.addScalar("item_code", String.class);
        query.addScalar("item_name", String.class);
        query.addScalar("policy_version_id", Long.class);
        query.addScalar("inclusion_decision_status", String.class);
        query.addScalar("exclusion_type", String.class);
        query.addScalar("inclusion_decision_reason", String.class);
        query.addScalar("allocation_basis", String.class);
        query.addScalar("evidence_ref", String.class);
        query.addScalar("attribution_method", String.class);
        query.addScalar("contract_date", LocalDate.class);
        return query.setTupleTransformer((row, aliases) -> new ConfirmationData(
                (Long) row[0],
                enumValue(CommissionPaymentStatus.class, row[1]),
                (BigDecimal) row[2],
                (BigDecimal) row[3],
                (BigDecimal) row[4],
                (String) row[5],
                (LocalDate) row[6],
                (LocalDate) row[7],
                (Long) row[8],
                (Long) row[9],
                (Long) row[10],
                enumValue(PaymentStage.class, row[11]),
                (Long) row[12],
                (String) row[13],
                (String) row[14],
                (Long) row[15],
                enumValue(InclusionDecisionStatus.class, row[16]),
                enumValue(ExclusionType.class, row[17]),
                (String) row[18],
                (String) row[19],
                (String) row[20],
                enumValue(AttributionMethod.class, row[21]),
                (LocalDate) row[22]
        )).getResultList();
    }

    public List<ConfirmationData> findConfirmationData(Long paymentId) {
        NativeQuery<?> query = nativeQuery(FIND_CONFIRMATION_DATA);
        query.setParameter("paymentId", paymentId);
        query.addScalar("payment_id", Long.class);
        query.addScalar("status", String.class);
        query.addScalar("amount", BigDecimal.class);
        query.addScalar("attributed_amount", BigDecimal.class);
        query.addScalar("total_attributed_amount", BigDecimal.class);
        query.addScalar("confirm_idempotency_key", String.class);
        query.addScalar("attribution_date", LocalDate.class);
        query.addScalar("attribution_month", LocalDate.class);
        query.addScalar("transaction_attribution_id", Long.class);
        query.addScalar("contract_id", Long.class);
        query.addScalar("agent_id", Long.class);
        query.addScalar("payment_stage", String.class);
        query.addScalar("commission_item_id", Long.class);
        query.addScalar("item_code", String.class);
        query.addScalar("item_name", String.class);
        query.addScalar("policy_version_id", Long.class);
        query.addScalar("inclusion_decision_status", String.class);
        query.addScalar("exclusion_type", String.class);
        query.addScalar("inclusion_decision_reason", String.class);
        query.addScalar("allocation_basis", String.class);
        query.addScalar("evidence_ref", String.class);
        query.addScalar("attribution_method", String.class);
        query.addScalar("contract_date", LocalDate.class);
        return query.setTupleTransformer((row, aliases) -> new ConfirmationData(
                (Long) row[0],
                enumValue(CommissionPaymentStatus.class, row[1]),
                (BigDecimal) row[2],
                (BigDecimal) row[3],
                (BigDecimal) row[4],
                (String) row[5],
                (LocalDate) row[6],
                (LocalDate) row[7],
                (Long) row[8],
                (Long) row[9],
                (Long) row[10],
                enumValue(PaymentStage.class, row[11]),
                (Long) row[12],
                (String) row[13],
                (String) row[14],
                (Long) row[15],
                enumValue(InclusionDecisionStatus.class, row[16]),
                enumValue(ExclusionType.class, row[17]),
                (String) row[18],
                (String) row[19],
                (String) row[20],
                enumValue(AttributionMethod.class, row[21]),
                (LocalDate) row[22]
        )).getResultList();
    }

    public List<AttributedContractNo> findAttributedContractNumbers(Long paymentId) {
        NativeQuery<?> query = nativeQuery(FIND_ATTRIBUTED_CONTRACT_NUMBERS);
        query.setParameter("paymentId", paymentId);
        query.addScalar("contract_id", Long.class);
        query.addScalar("contract_no", String.class);
        return query.setTupleTransformer((row, aliases) -> new AttributedContractNo(
                (Long) row[0],
                (String) row[1]
        )).getResultList();
    }

    public List<Long> lockAttributedContracts(Long paymentId) {
        NativeQuery<?> query = nativeQuery(LOCK_ATTRIBUTED_CONTRACTS);
        query.setParameter("paymentId", paymentId);
        return query.setTupleTransformer((row, aliases) -> ((Number) row[0]).longValue())
                .getResultList();
    }

    public CapRuleSnapshot findCapRuleSnapshot(Long paymentId, Long transactionAttributionId) {
        NativeQuery<?> query = nativeQuery(FIND_CAP_RULE_SNAPSHOT);
        query.setParameter("paymentId", paymentId);
        query.setParameter("transactionAttributionId", transactionAttributionId);
        query.addScalar("cap_rule_set_id", Long.class);
        query.addScalar("cap_rule_item_id", Long.class);
        query.addScalar("rule_inclusion_status", String.class);
        query.addScalar("decision_reason", String.class);
        query.addScalar("base_premium_amount", BigDecimal.class);
        query.addScalar("premium_multiplier", BigDecimal.class);
        query.addScalar("warning_usage_pct", BigDecimal.class);
        query.addScalar("existing_included_amount", BigDecimal.class);
        query.addScalar("compliance_evidence_amount", BigDecimal.class);
        return query.setTupleTransformer((row, aliases) -> new CapRuleSnapshot(
                (Long) row[0],
                (Long) row[1],
                enumValue(InclusionDecisionStatus.class, row[2]),
                (String) row[3],
                (BigDecimal) row[4],
                (BigDecimal) row[5],
                (BigDecimal) row[6],
                (BigDecimal) row[7],
                (BigDecimal) row[8]
        )).getResultList().stream().findFirst().orElse(null);
    }

    public boolean existsApplicableCapRuleSet(Long paymentId, Long transactionAttributionId) {
        NativeQuery<?> query = nativeQuery(EXISTS_APPLICABLE_CAP_RULE_SET);
        query.setParameter("paymentId", paymentId);
        query.setParameter("transactionAttributionId", transactionAttributionId);
        return query.setTupleTransformer((row, aliases) -> (Boolean) row[0])
                .getResultList().stream().findFirst().orElse(false);
    }

    public List<ExistingIncludedDetail> findExistingIncludedDetails(Long paymentId, Long transactionAttributionId) {
        NativeQuery<?> query = nativeQuery(FIND_EXISTING_INCLUDED_DETAILS);
        query.setParameter("paymentId", paymentId);
        query.setParameter("transactionAttributionId", transactionAttributionId);
        query.addScalar("transaction_attribution_id", Long.class);
        query.addScalar("commission_item_id", Long.class);
        query.addScalar("item_code", String.class);
        query.addScalar("item_name", String.class);
        query.addScalar("classification_snapshot", String.class);
        query.addScalar("amount", BigDecimal.class);
        query.addScalar("decision_reason", String.class);
        query.addScalar("evidence_ref", String.class);
        return query.setTupleTransformer((row, aliases) -> new ExistingIncludedDetail(
                (Long) row[0],
                (Long) row[1],
                (String) row[2],
                (String) row[3],
                (String) row[4],
                (BigDecimal) row[5],
                (String) row[6],
                (String) row[7]
        )).getResultList();
    }

    public List<Long> findCapCheckIds(Long paymentId) {
        NativeQuery<?> query = nativeQuery(FIND_CAP_CHECK_IDS);
        query.setParameter("paymentId", paymentId);
        return query.setTupleTransformer((row, aliases) -> ((Number) row[0]).longValue())
                .getResultList();
    }

    private SearchFilter searchFilter(CommissionPaymentSearchCondition condition) {
        StringBuilder sql = new StringBuilder("WHERE 1 = 1\n");
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (condition.getSettlementMonth() != null && !condition.getSettlementMonth().isEmpty()) {
            sql.append("""
                    AND ct.settlement_month = CAST(CONCAT(:settlementMonth, '-01') AS date)
                    """);
            parameters.put("settlementMonth", condition.getSettlementMonth());
        }
        if (condition.getPaymentStage() != null) {
            sql.append("""
                    AND ct.payment_stage = :paymentStage
                    """);
            parameters.put("paymentStage", condition.getPaymentStage().name());
        }
        if (condition.getStatus() != null) {
            sql.append("""
                    AND ct.status = :status
                    """);
            parameters.put("status", condition.getStatus().name());
        }
        if (condition.getSourceType() != null && !condition.getSourceType().isEmpty()) {
            sql.append("""
                    AND ct.source_type = :sourceType
                    """);
            parameters.put("sourceType", condition.getSourceType());
        }
        if (condition.getInsurerId() != null) {
            sql.append("""
                    AND COALESCE(ct.insurer_id, source_contract.insurer_id) = :insurerId
                    """);
            parameters.put("insurerId", condition.getInsurerId());
        }
        if (condition.getContractNo() != null && !condition.getContractNo().isEmpty()) {
            sql.append("""
                    AND (
                        source_contract.contract_no ILIKE CONCAT('%', :contractNo, '%')
                        OR EXISTS (
                            SELECT 1
                              FROM fgc.transaction_attribution filter_ta
                              JOIN fgc.insurance_contract filter_contract
                                ON filter_contract.contract_id = filter_ta.contract_id
                             WHERE filter_ta.commission_transaction_id = ct.commission_transaction_id
                               AND filter_contract.contract_no ILIKE CONCAT('%', :contractNo, '%')
                        )
                    )
                    """);
            parameters.put("contractNo", condition.getContractNo());
        }
        if (condition.getAgentId() != null) {
            sql.append("""
                    AND ct.recipient_agent_id = :agentId
                    """);
            parameters.put("agentId", condition.getAgentId());
        }
        if (condition.getCommissionItemId() != null) {
            sql.append("""
                    AND ct.commission_item_id = :commissionItemId
                    """);
            parameters.put("commissionItemId", condition.getCommissionItemId());
        }
        if (Boolean.TRUE.equals(condition.getNoAttributionOnly())) {
            sql.append("""
                    AND NOT EXISTS (
                        SELECT 1
                          FROM fgc.transaction_attribution noattr_ta
                         WHERE noattr_ta.commission_transaction_id = ct.commission_transaction_id
                    )
                    """);
        }
        if (Boolean.TRUE.equals(condition.getAttributionImbalanceOnly())) {
            sql.append("""
                    AND EXISTS (
                        SELECT 1
                          FROM fgc.vw_transaction_attribution_balance imbalance
                         WHERE imbalance.commission_transaction_id = ct.commission_transaction_id
                    )
                    """);
        }
        return new SearchFilter(sql.toString(), parameters);
    }

    private record SearchFilter(String sql, Map<String, Object> parameters) { }

    private NativeQuery<?> nativeQuery(String sql) {
        // JPA 쓰기와 잔여 MyBatis 읽기가 공존하므로 스냅샷/잠금 전에 미반영 변경을 반영한다.
        if (entityManager.isJoinedToTransaction() && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            entityManager.flush();
        }
        return entityManager.createNativeQuery(sql).unwrap(NativeQuery.class);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value) {
        return value == null ? null : Enum.valueOf(type, value.toString());
    }
}
