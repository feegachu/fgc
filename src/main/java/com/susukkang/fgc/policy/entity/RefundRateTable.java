package com.susukkang.fgc.policy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 상품·납입기간·채널별 예상 해약환급률표의 헤더.
 */
@Entity
@Table(name = "refund_rate_table", uniqueConstraints = {
        @UniqueConstraint(name = "uq_refund_table_scope", columnNames = {
                "insurer_id", "product_id", "payment_term_months", "channel_code", "effective_from"
        })
})
@Getter
public class RefundRateTable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refund_rate_table_id", nullable = false)
    private Long refundRateTableId;

    @Column(name = "policy_version_id", nullable = false)
    private Long policyVersionId;

    @Column(name = "insurer_id", nullable = false)
    private Long insurerId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_offering_id")
    private Long productOfferingId;

    @Column(name = "payment_term_months", nullable = false)
    private Integer paymentTermMonths;

    @Column(name = "channel_code", nullable = false, length = 30)
    private String channelCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "representative_attributes", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> representativeAttributes = new HashMap<>();

    @Column(name = "average_declared_rate_pct", precision = 9, scale = 6)
    private BigDecimal averageDeclaredRatePct;

    @Column(name = "standard_deduction_80_yn", nullable = false)
    private boolean standardDeduction80Yn;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "source_product_code", nullable = false, length = 60)
    private String sourceProductCode;

    @Column(name = "source_document_ref", nullable = false, length = 500)
    private String sourceDocumentRef;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
