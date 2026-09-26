package com.susukkang.fgc.policy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 설명 : 예상 해약환급률표의 차월별 환급률.
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
@Entity
@Table(name = "refund_rate_line", uniqueConstraints = {
        @UniqueConstraint(name = "uq_refund_rate_month", columnNames = {"refund_rate_table_id", "contract_month_no"})
})
@Getter
public class RefundRateLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refund_rate_line_id", nullable = false)
    private Long refundRateLineId;

    @Column(name = "refund_rate_table_id", nullable = false)
    private Long refundRateTableId;

    @Column(name = "contract_month_no", nullable = false)
    private Integer contractMonthNo;

    @Column(name = "refund_rate_pct", nullable = false, precision = 9, scale = 6)
    private BigDecimal refundRatePct;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
