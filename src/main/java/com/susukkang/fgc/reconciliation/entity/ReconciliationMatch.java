package com.susukkang.fgc.reconciliation.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 설명 : 대사 결과의 예상 스케줄·실제 지급 귀속 원천 연결 매핑.
 *
 * @author C4t4ddict
 * @since 2026-10-05
 * @version 1.0
 */
@Entity
@Table(name = "reconciliation_match", schema = "fgc")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationMatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reconciliation_match_id")
    private Long reconciliationMatchId;
    @Column(name = "reconciliation_result_id", nullable = false)
    private Long reconciliationResultId;
    @Column(name = "match_seq", nullable = false)
    private Integer matchSeq;
    @Column(name = "schedule_line_id")
    private Long scheduleLineId;
    @Column(name = "transaction_attribution_id")
    private Long transactionAttributionId;
    @Column(name = "matched_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal matchedAmount;
    @Column(name = "match_role", nullable = false, length = 20)
    private String matchRole;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
