package com.susukkang.fgc.cap.mapper;

import com.susukkang.fgc.cap.dto.CapIncludedAmountSummary;
import com.susukkang.fgc.common.code.PaymentStage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 설명 : 계약별 한도 산입 지급액 합계 조회를 담당하는 MyBatis Mapper
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Mapper
public interface CapIncludedAmountMapper {

    /**
     * 설명 : 지급 확정 직전 기존 확정액과 현재 지급 건의 계약·설계사별 산입액을 조회한다
     *
     * @param contractId 계약 ID
     * @param transactionId 현재 확정할 지급 건 ID
     * @param paymentStage 지급 단계
     * @return 계약·설계사별 한도 산입액 합계 목록
     * @author hjKang
     * @since 2026-08-12
     */
    List<CapIncludedAmountSummary> sumIncludedAmountByContractAndAgent(
            @Param("contractId") Long contractId,
            @Param("transactionId") Long transactionId,
            @Param("paymentStage") PaymentStage paymentStage);

    /**
     * 설명 : 월 검증 시 확정 지급 건의 계약·설계사별 산입액을 전체 재조회한다
     *
     * @param contractId 계약 ID
     * @param paymentStage 지급 단계
     * @return 계약·설계사별 확정 한도 산입액 합계 목록
     * @author hjKang
     * @since 2026-08-12
     */
    List<CapIncludedAmountSummary> sumConfirmedIncludedAmountByContractAndAgent(
            @Param("contractId") Long contractId,
            @Param("paymentStage") PaymentStage paymentStage);
}
