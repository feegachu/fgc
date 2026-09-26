package com.susukkang.fgc.policy.repository;

import com.susukkang.fgc.policy.dto.RefundRateTableDetailRow;
import com.susukkang.fgc.policy.entity.RefundRateTable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RefundRateTableRepository extends JpaRepository<RefundRateTable, Long> {

    // productOfferingId는 대표 예시이므로 상품명은 productId로 조회한다.
    @Query("""
            SELECT new com.susukkang.fgc.policy.dto.RefundRateTableDetailRow(
                rrt.refundRateTableId,
                i.insurerName,
                p.productName,
                rrt.paymentTermMonths,
                rrt.channelCode,
                rrt.averageDeclaredRatePct,
                rrt.standardDeduction80Yn,
                rrt.effectiveFrom,
                rrt.effectiveTo,
                rrt.sourceProductCode,
                rrt.sourceDocumentRef,
                rrl.contractMonthNo,
                rrl.refundRatePct)
            FROM RefundRateTable rrt
            JOIN Insurer i ON i.insurerId = rrt.insurerId
            JOIN Product p ON p.productId = rrt.productId
            LEFT JOIN RefundRateLine rrl ON rrl.refundRateTableId = rrt.refundRateTableId
            WHERE rrt.policyVersionId = :policyVersionId
            ORDER BY i.insurerName, p.productName, rrt.paymentTermMonths,
                     rrt.refundRateTableId, rrl.contractMonthNo
            """)
    List<RefundRateTableDetailRow> selectRefundRateTables(@Param("policyVersionId") Long policyVersionId);
}
