package com.susukkang.fgc.cap.entity;

import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 설명 : 한도 산입·제외 항목과 계산 당시 코드·명칭·회차를 보존하는 결과 상세 엔티티.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-30
 */
@Entity
@Table(name = "cap_check_detail", schema = "fgc", uniqueConstraints = {
        @UniqueConstraint(name = "uq_cap_check_detail", columnNames = {"cap_check_id", "detail_seq"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CapCheckDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cap_check_detail_id")
    private Long capCheckDetailId;

    @Column(name = "cap_check_id", nullable = false)
    private Long capCheckId;

    @Column(name = "detail_seq", nullable = false)
    private Integer detailSeq;

    @Column(name = "commission_item_id", nullable = false)
    private Long commissionItemId;

    @Column(name = "item_code", nullable = false, length = 50)
    private String itemCode;

    @Column(name = "item_name", nullable = false, length = 120)
    private String itemName;

    @Column(name = "transaction_attribution_id")
    private Long transactionAttributionId;

    @Column(name = "schedule_line_id")
    private Long scheduleLineId;

    @Column(name = "contract_month_no")
    private Integer contractMonthNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification_snapshot", nullable = false, length = 20)
    private InclusionDecisionStatus classificationSnapshot;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "decision_reason", nullable = false, length = 1000)
    private String decisionReason;

    @Column(name = "evidence_ref", length = 500)
    private String evidenceRef;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private CapCheckDetail(Long capCheckId, Integer detailSeq, Long commissionItemId, String itemCode, String itemName, Long transactionAttributionId, Long scheduleLineId, Integer contractMonthNo, InclusionDecisionStatus classificationSnapshot, BigDecimal amount, String decisionReason, String evidenceRef) {
        this.capCheckId = capCheckId;
        this.detailSeq = detailSeq;
        this.commissionItemId = commissionItemId;
        this.itemCode = itemCode;
        this.itemName = itemName;
        this.transactionAttributionId = transactionAttributionId;
        this.scheduleLineId = scheduleLineId;
        this.contractMonthNo = contractMonthNo;
        this.classificationSnapshot = classificationSnapshot;
        this.amount = amount;
        this.decisionReason = decisionReason;
        this.evidenceRef = evidenceRef;
    }
}
