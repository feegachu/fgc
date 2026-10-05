package com.susukkang.fgc.base.repository;

import com.susukkang.fgc.base.entity.ProductOffering;
import com.susukkang.fgc.validation.dto.ProductOfferingSnapshotView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 상품 판매버전(product_offering) 기본 조회 Repository.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-10-03
 */
public interface ProductOfferingRepository extends JpaRepository<ProductOffering, Long> {

    // #379 validation_run 생성 시 policy_snapshot 조립용. asOfDate 시점 판매 중인 전체를 나열한다.
    @Query("""
            SELECT new com.susukkang.fgc.validation.dto.ProductOfferingSnapshotView(
                po.productOfferingId,
                po.productId,
                po.channelCode,
                po.feeRegimeCode,
                po.standardDeduction80Yn)
            FROM ProductOffering po
            WHERE po.activeYn = true
              AND po.salesStartDate <= :asOfDate
              AND (po.salesEndDate IS NULL OR po.salesEndDate >= :asOfDate)
            ORDER BY po.productOfferingId
            """)
    List<ProductOfferingSnapshotView> findActiveProductOfferings(@Param("asOfDate") LocalDate asOfDate);
}
