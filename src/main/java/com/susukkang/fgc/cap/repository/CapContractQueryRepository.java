package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.dto.CapContractView;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설명 : 기존 계약·판매버전·상품 엔티티에서 한도 계산 입력을 DTO로 조회한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-10-02
 */
@Transactional(readOnly = true)
public interface CapContractQueryRepository extends Repository<InsuranceContract, Long> {

    /** 계약에 연결된 판매버전·상품을 조회하며, 계약이 없으면 null을 반환한다. */
    @Query("""
            SELECT new com.susukkang.fgc.cap.dto.CapContractView(
                c.contractId,
                c.contractDate,
                c.monthlyEquivalentFirstPremium,
                c.paymentTermMonths,
                c.standardSurrenderDeductionAmount,
                c.insurerId,
                po.productOfferingId,
                po.channelCode,
                po.standardDeduction80Yn,
                p.productId,
                p.productGroupCode
            )
              FROM InsuranceContract c, ProductOffering po, Product p
             WHERE c.contractId = :contractId
               AND po.productOfferingId = c.productOfferingId
               AND p.productId = po.productId
            """)
    CapContractView findCapViewByContractId(@Param("contractId") Long contractId);
}
