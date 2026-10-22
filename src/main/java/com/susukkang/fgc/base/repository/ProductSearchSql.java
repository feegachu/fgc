package com.susukkang.fgc.base.repository;

/**
 * 상품 판매버전 목록 SQL(#604). 기본 조회(ProductRepository)와 정렬 조회(ProductSearchImpl)가 같은 본문을 쓴다.
 * 납입기간은 판매버전 ID가 아닌 상품·채널별 최신 활성 환수율표에서 가져온다.
 */
final class ProductSearchSql {

    private ProductSearchSql() {
    }

    /** 컬럼·조인·조건까지. ORDER BY 는 붙이지 않는다. */
    static final String BODY = """
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
              AND (:includeHistorical = TRUE OR p.active_yn = true)
              AND (:includeHistorical = TRUE OR po.active_yn = true)
              AND (:includeHistorical = TRUE OR po.sales_start_date <= :asOf)
              AND (:includeHistorical = TRUE OR :includeExpired = TRUE OR po.sales_end_date IS NULL OR po.sales_end_date >= :asOf)
            """;

    /** 기존 기본 정렬 */
    static final String DEFAULT_ORDER = """
            ORDER BY p.insurer_product_code, po.offering_version, po.channel_code, po.product_offering_id
            """;

    static final String COUNT = """
            SELECT COUNT(*)
            FROM fgc.product p
            JOIN fgc.product_offering po ON po.product_id = p.product_id
            WHERE p.insurer_id = :insurerId
              AND (:includeHistorical = TRUE OR p.active_yn = true)
              AND (:includeHistorical = TRUE OR po.active_yn = true)
              AND (:includeHistorical = TRUE OR po.sales_start_date <= :asOf)
              AND (:includeHistorical = TRUE OR :includeExpired = TRUE OR po.sales_end_date IS NULL OR po.sales_end_date >= :asOf)
            """;
}
