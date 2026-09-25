package com.susukkang.fgc.base.entity;

import com.susukkang.fgc.base.dto.ProductRow;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 설명 : Product
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-25
 */
@Entity
@Table(name = "product")
@SqlResultSetMapping(
        name = "ProductRowMapping",
        classes = @ConstructorResult(
                targetClass = ProductRow.class,
                columns = {
                        @ColumnResult(name = "product_offering_id", type = Long.class),
                        @ColumnResult(name = "insurer_product_code", type = String.class),
                        @ColumnResult(name = "standard_product_code", type = String.class),
                        @ColumnResult(name = "product_name", type = String.class),
                        @ColumnResult(name = "product_group_code", type = String.class),
                        @ColumnResult(name = "offering_version", type = String.class),
                        @ColumnResult(name = "sales_start_date", type = LocalDate.class),
                        @ColumnResult(name = "sales_end_date", type = LocalDate.class),
                        @ColumnResult(name = "basic_document_version", type = String.class),
                        @ColumnResult(name = "basic_document_date", type = LocalDate.class),
                        @ColumnResult(name = "channel_code", type = String.class),
                        @ColumnResult(name = "channel_special_rule_yn", type = Boolean.class),
                        @ColumnResult(name = "fee_regime_code", type = String.class),
                        @ColumnResult(name = "standard_deduction_80_yn", type = Boolean.class),
                        @ColumnResult(name = "payment_term_months", type = Integer.class)
                }
        )
)
@Getter
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "insurer_id", nullable = false)
    private Long insurerId;

    @Column(name = "insurer_product_code", nullable = false, length = 60)
    private String insurerProductCode;

    @Column(name = "standard_product_code", nullable = false, length = 60)
    private String standardProductCode;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "product_group_code", nullable = false, length = 40)
    private String productGroupCode;

    @Column(name = "protection_type", nullable = false, length = 20)
    private String protectionType = "PROTECTION";

    @Column(name = "active_yn", nullable = false)
    private boolean activeYn = true;
}
