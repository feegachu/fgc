package com.susukkang.fgc.base.repository;

import com.susukkang.fgc.base.dto.CommissionItemResponse;
import com.susukkang.fgc.base.entity.CommissionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : CommissionItemRepository
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-25
 */
public interface CommissionItemRepository extends JpaRepository<CommissionItem,Long> {
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
