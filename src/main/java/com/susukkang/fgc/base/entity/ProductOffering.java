package com.susukkang.fgc.base.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 설명 : 상품의 판매버전·채널 및 적용기간 매핑
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
@Entity
@Table(name = "product_offering")
@Getter
public class ProductOffering {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_offering_id", nullable = false)
    private Long productOfferingId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "offering_version", nullable = false, length = 40)
    private String offeringVersion;

    @Column(name = "sales_start_date", nullable = false)
    private LocalDate salesStartDate;

    @Column(name = "sales_end_date")
    private LocalDate salesEndDate;

    @Column(name = "basic_document_version", nullable = false, length = 80)
    private String basicDocumentVersion;

    @Column(name = "basic_document_date", nullable = false)
    private LocalDate basicDocumentDate;

    @Column(name = "channel_code", nullable = false, length = 30)
    private String channelCode;

    @Column(name = "channel_special_rule_yn", nullable = false)
    private boolean channelSpecialRuleYn;

    @Column(name = "fee_regime_code", nullable = false, length = 40)
    private String feeRegimeCode;

    @Column(name = "standard_deduction_80_yn", nullable = false)
    private boolean standardDeduction80Yn;

    @Column(name = "active_yn", nullable = false)
    private boolean activeYn = true;
}
