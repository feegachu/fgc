package com.susukkang.fgc.base.repository;

import com.susukkang.fgc.base.dto.CommissionItemResponse;
import com.susukkang.fgc.base.entity.CommissionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 기준일에 유효한 수수료 항목을 조회하는 Repository
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
public interface CommissionItemRepository extends JpaRepository<CommissionItem,Long> {
    @Query("""
        SELECT new com.susukkang.fgc.base.dto.CommissionItemResponse(
            c.commissionItemId, c.itemCode, c.itemName, c.cashflowType, c.itemCategory, c.effectiveFrom, c.effectiveTo)
        FROM CommissionItem c WHERE c.activeYn = true
          AND c.effectiveFrom <= :asOf AND (c.effectiveTo IS NULL OR c.effectiveTo >= :asOf)
          AND (CAST(:selectedId AS Long) IS NULL OR c.commissionItemId = :selectedId)
          AND (:keyword = '' OR LOCATE(LOWER(:keyword), LOWER(c.itemCode)) > 0
              OR LOCATE(LOWER(:keyword), LOWER(c.itemName)) > 0)
          AND (:cashflowType = '' OR c.cashflowType = :cashflowType)
        ORDER BY c.itemCode, c.commissionItemId
        """)
    Page<CommissionItemResponse> searchItems(
            @Param("asOf") LocalDate asOf, @Param("keyword") String keyword,
            @Param("cashflowType") String cashflowType, @Param("selectedId") Long selectedId,
            Pageable pageable);

    @Query(value = """
        SELECT new com.susukkang.fgc.base.dto.CommissionItemResponse(
            c.commissionItemId,
            c.itemCode,
            c.itemName,
            c.cashflowType,
            c.itemCategory,
            c.effectiveFrom,
            c.effectiveTo
        )
        FROM CommissionItem c
        WHERE c.activeYn = true
          AND c.effectiveFrom <= :asOf
          AND (c.effectiveTo IS NULL OR c.effectiveTo >= :asOf)
        ORDER BY c.itemCode
    """)
    List<CommissionItemResponse> findEffectiveItems(
            @Param("asOf") LocalDate asOf
    );
}
