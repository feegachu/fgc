package com.susukkang.fgc.base.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import org.hibernate.annotations.Check;

import java.time.LocalDate;

/**
 * 설명 : 수수료 항목의 코드·분류 및 적용기간을 매핑하는 엔티티
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
@Entity
@Table(name = "commission_item", uniqueConstraints = {
        @UniqueConstraint(name = "uq_commission_item_code", columnNames = "item_code")
})
@Check(name = "ck_commission_item_period",
        constraints = "effective_to IS NULL OR effective_to >= effective_from")
@Getter
public class CommissionItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "commission_item_id", nullable = false)
    private Long commissionItemId;

    @Column(name = "item_code", nullable = false, length = 50)
    private String itemCode;

    @Column(name = "item_name", nullable = false, length = 120)
    private String itemName;

    @Column(name = "cashflow_type", nullable = false, length = 15)
    @Check(constraints = "cashflow_type IN ('PAYMENT', 'DEDUCTION')")
    private String cashflowType;

    @Column(name = "item_category", nullable = false, length = 40)
    private String itemCategory;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "active_yn", nullable = false)
    private boolean activeYn = true;
}
