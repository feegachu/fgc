package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.dto.RefundRateTableView;
import com.susukkang.fgc.policy.entity.RefundRateTable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 기준일에 적용할 예상 해약환급률표와 차월별 환급률을 조회한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-10-02
 */
@Transactional(readOnly = true)
public interface RefundRateQueryRepository extends Repository<RefundRateTable, Long> {

    /** 유효한 ACTIVE 정책의 표 중 적용 시작일이 가장 최근인 한 건을 조회하며 없으면 null을 반환한다. */
    default RefundRateTableView findApplicableTable(
            Long insurerId,
            Long productId,
            Integer paymentTermMonths,
            String channelCode,
            LocalDate asOfDate
    ) {
        return findApplicableTables(insurerId, productId, paymentTermMonths, channelCode, asOfDate,
                PageRequest.of(0, 1)).stream().findFirst().orElse(null);
    }

    // 표의 범위는 보험사·상품·납입기간·채널이다. 판매버전으로 조인하지 않는다.
    @Query("""
            SELECT new com.susukkang.fgc.cap.dto.RefundRateTableView(
                rrt.refundRateTableId,
                rrt.policyVersionId,
                pv.versionNo,
                rrt.standardDeduction80Yn,
                rrt.effectiveFrom
            )
              FROM RefundRateTable rrt, PolicyVersion pv
             WHERE pv.policyVersionId = rrt.policyVersionId
               AND pv.status = com.susukkang.fgc.common.code.PolicyStatus.ACTIVE
               AND rrt.insurerId = :insurerId
               AND rrt.productId = :productId
               AND rrt.paymentTermMonths = :paymentTermMonths
               AND rrt.channelCode = :channelCode
               AND rrt.effectiveFrom <= :asOfDate
               AND (rrt.effectiveTo IS NULL OR rrt.effectiveTo >= :asOfDate)
             ORDER BY rrt.effectiveFrom DESC
            """)
    List<RefundRateTableView> findApplicableTables(
            @Param("insurerId") Long insurerId,
            @Param("productId") Long productId,
            @Param("paymentTermMonths") Integer paymentTermMonths,
            @Param("channelCode") String channelCode,
            @Param("asOfDate") LocalDate asOfDate,
            Pageable pageable
    );

    /** 표의 특정 차월 환급률을 조회하며 해당 차월이 없으면 null을 반환한다. */
    @Query("""
            SELECT rrl.refundRatePct
              FROM RefundRateLine rrl
             WHERE rrl.refundRateTableId = :refundRateTableId
               AND rrl.contractMonthNo = :contractMonthNo
            """)
    BigDecimal findRateAtMonth(
            @Param("refundRateTableId") Long refundRateTableId,
            @Param("contractMonthNo") int contractMonthNo
    );
}
