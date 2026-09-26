package com.susukkang.fgc.base.repository;

import com.susukkang.fgc.base.dto.ProductRow;
import com.susukkang.fgc.base.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

/**
 * 설명 : 상품 판매버전 및 적용 환급률표의 납입기간을 조회하는 Repository
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    // 납입기간은 판매버전 ID가 아닌 상품·채널별 최신 활성 환수율표에서 가져온다.
    @NativeQuery(value = """
            SELECT po.product_offering_id,
                   p.insurer_product_code,
                   p.standard_product_code,
                   p.product_name,
                   p.product_group_code,
                   po.offering_version,
                   po.sales_start_date,
                   po.sales_end_date,
                   po.basic_document_version,
                   po.basic_document_date,
                   po.channel_code,
                   po.channel_special_rule_yn,
                   po.fee_regime_code,
                   po.standard_deduction_80_yn,
                   (
                       SELECT rrt.payment_term_months
                       FROM fgc.refund_rate_table rrt
                       JOIN fgc.policy_version pv ON pv.policy_version_id = rrt.policy_version_id
                       WHERE rrt.insurer_id = p.insurer_id
                         AND rrt.product_id = p.product_id
                         AND rrt.channel_code = po.channel_code
                         AND pv.status = 'ACTIVE'
                         AND rrt.effective_from <= :asOf
                         AND (rrt.effective_to IS NULL OR rrt.effective_to >= :asOf)
                       ORDER BY rrt.effective_from DESC, rrt.refund_rate_table_id DESC
                       LIMIT 1
                   ) AS payment_term_months
            FROM fgc.product p
            JOIN fgc.product_offering po ON po.product_id = p.product_id
            WHERE p.insurer_id = :insurerId
              AND p.active_yn = true
              AND po.active_yn = true
              AND po.sales_start_date <= :asOf
              AND (po.sales_end_date IS NULL OR po.sales_end_date >= :asOf)
            ORDER BY p.insurer_product_code, po.offering_version, po.channel_code, po.product_offering_id
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM fgc.product p
            JOIN fgc.product_offering po ON po.product_id = p.product_id
            WHERE p.insurer_id = :insurerId
              AND p.active_yn = true
              AND po.active_yn = true
              AND po.sales_start_date <= :asOf
              AND (po.sales_end_date IS NULL OR po.sales_end_date >= :asOf)
            """,
            sqlResultSetMapping = "ProductRowMapping")
    Page<ProductRow> search(
            @Param("insurerId") long insurerId,
            @Param("asOf") LocalDate asOf,
            Pageable pageable
    );
}
