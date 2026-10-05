package com.susukkang.fgc.transaction.entity;

import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.ExclusionType;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.transaction.domain.CommissionPaymentAttributionCommand;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 설명 : 지급 금액의 계약·설계사별 귀속과 당시 산입 판정 스냅샷.
 *
 * @author hjKang
 * @since 2026-09-30
 * @version 1.0
 */
@Entity
@Table(name = "transaction_attribution", schema = "fgc", uniqueConstraints =
        @UniqueConstraint(name = "uq_transaction_attribution",
                columnNames = {"commission_transaction_id", "attribution_seq"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionAttribution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transaction_attribution_id")
    private Long transactionAttributionId;
    @Column(name = "commission_transaction_id", nullable = false)
    private Long commissionTransactionId;
    @Column(name = "attribution_seq", nullable = false)
    private Integer attributionSequence;
    @Column(name = "attribution_scope", nullable = false, length = 20)
    private String attributionScope;
    @Column(name = "contract_id")
    private Long contractId;
    @Column(name = "schedule_line_id")
    private Long scheduleLineId;
    @Column(name = "agent_id")
    private Long agentId;
    @Column(name = "source_agent_code", length = 80)
    private String sourceAgentCode;
    @Column(name = "attribution_date", nullable = false)
    private LocalDate attributionDate;
    @Column(name = "attribution_month", nullable = false)
    private LocalDate attributionMonth;
    @Column(name = "attributed_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal attributedAmount;
    @Column(name = "cap_rule_item_id")
    private Long capRuleItemId;
    @Enumerated(EnumType.STRING)
    @Column(name = "inclusion_status_snapshot", nullable = false, length = 20)
    private InclusionDecisionStatus inclusionStatusSnapshot;
    @Enumerated(EnumType.STRING)
    @Column(name = "exclusion_type_snapshot", length = 40)
    private ExclusionType exclusionTypeSnapshot;
    @Enumerated(EnumType.STRING)
    @Column(name = "attribution_method", nullable = false, length = 40)
    private AttributionMethod attributionMethod;
    @Column(name = "allocation_policy_id")
    private Long allocationPolicyId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allocation_basis_snapshot", nullable = false, columnDefinition = "jsonb")
    private String allocationBasisSnapshot;
    @Column(name = "evidence_ref", length = 500)
    private String evidenceRef;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static TransactionAttribution from(CommissionPaymentAttributionCommand command) {
        TransactionAttribution attribution = new TransactionAttribution();
        attribution.commissionTransactionId = command.getPaymentId();
        attribution.attributionSequence = command.getAttributionSequence();
        attribution.attributionScope = command.getAttributionScope();
        attribution.contractId = command.getContractId();
        attribution.scheduleLineId = command.getScheduleLineId();
        attribution.agentId = command.getAgentId();
        attribution.attributionDate = command.getAttributionDate();
        attribution.attributionMonth = command.getAttributionMonth();
        attribution.attributedAmount = command.getAmount();
        attribution.inclusionStatusSnapshot = command.getInclusionDecisionStatus();
        attribution.exclusionTypeSnapshot = command.getExclusionType() == ExclusionType.NONE
                ? null : command.getExclusionType();
        attribution.attributionMethod = command.getAttributionMethod();
        attribution.allocationPolicyId = command.getAllocationPolicyId();
        attribution.allocationBasisSnapshot = command.getAllocationBasisJson();
        attribution.evidenceRef = command.getEvidenceRef();
        return attribution;
    }
}
