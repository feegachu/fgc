package com.susukkang.fgc.transaction.entity;

import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.transaction.domain.CommissionPaymentCommand;
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
 * 설명 : 실제 수수료 지급 원장. 상태 전이와 멱등키는 지급 확정 저장소에서 함께 변경한다.
 *
 * @author hjKang
 * @since 2026-09-30
 * @version 1.0
 */
@Entity
@Table(name = "commission_transaction", schema = "fgc")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommissionTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "commission_transaction_id")
    private Long commissionTransactionId;
    @Column(name = "statement_batch_id")
    private Long statementBatchId;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_stage", nullable = false, length = 20)
    private PaymentStage paymentStage;
    @Column(name = "source_type", nullable = false, length = 35)
    private String sourceType;
    @Column(name = "source_business_key", nullable = false, length = 160)
    private String sourceBusinessKey;
    @Column(name = "source_contract_id")
    private Long sourceContractId;
    @Column(name = "insurer_id")
    private Long insurerId;
    @Column(name = "recipient_agent_id")
    private Long recipientAgentId;
    @Column(name = "commission_item_id", nullable = false)
    private Long commissionItemId;
    @Column(name = "policy_version_id")
    private Long policyVersionId;
    @Column(name = "settlement_month", nullable = false)
    private LocalDate settlementMonth;
    @Column(name = "due_date")
    private LocalDate dueDate;
    @Column(name = "paid_on")
    private LocalDate paidOn;
    @Column(name = "installment_no")
    private Integer installmentNo;
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;
    @Column(name = "cashflow_type", nullable = false, length = 15)
    private String cashflowType;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CommissionPaymentStatus status = CommissionPaymentStatus.DRAFT;
    @Column(name = "evidence_ref", length = 500)
    private String evidenceRef;
    @Column(name = "note", length = 1000)
    private String note;
    @Column(name = "created_by")
    private Long createdBy;
    @Column(name = "confirm_idempotency_key", length = 160)
    private String confirmIdempotencyKey;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "confirm_cap_check_ids", nullable = false, columnDefinition = "bigint[]")
    private Long[] confirmCapCheckIds = new Long[0];
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    public static CommissionTransaction draft(CommissionPaymentCommand command, Long insurerId) {
        CommissionTransaction transaction = new CommissionTransaction();
        transaction.updateDetails(command, insurerId);
        return transaction;
    }

    /** ID·상태·확정 결과를 제외한 수정 허용 필드만 변경한다. */
    private void updateDetails(CommissionPaymentCommand command, Long insurerId) {
        this.paymentStage = command.getPaymentStage();
        this.sourceType = command.getSourceType();
        this.sourceBusinessKey = command.getSourceBusinessKey();
        this.sourceContractId = command.getNaturalContractId();
        this.insurerId = insurerId;
        this.recipientAgentId = command.getAgentId();
        this.commissionItemId = command.getCommissionItemId();
        this.policyVersionId = command.getPolicyVersionId();
        this.settlementMonth = command.getSettlementMonth();
        this.dueDate = command.getDueDate();
        this.installmentNo = command.getInstallmentNo();
        this.amount = command.getAmount();
        this.cashflowType = command.getCashflowType();
        this.evidenceRef = command.getEvidenceRef();
        this.note = command.getNote();
    }
}
