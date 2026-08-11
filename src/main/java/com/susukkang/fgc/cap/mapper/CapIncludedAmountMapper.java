package com.susukkang.fgc.cap.mapper;

import com.susukkang.fgc.cap.dto.CapIncludedAmountSummary;
import com.susukkang.fgc.common.code.PaymentStage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CapIncludedAmountMapper {

    // 지급 확정 직전 기존 확정액과 현재 지급 건의 계약·설계사별 산입액 조회
    List<CapIncludedAmountSummary> sumIncludedAmountByContractAndAgent(
            @Param("contractId") Long contractId,
            @Param("transactionId") Long transactionId,
            @Param("paymentStage") PaymentStage paymentStage);

    // 월 검증 시 확정 지급 건의 계약·설계사별 산입액 전체 재조회
    List<CapIncludedAmountSummary> sumConfirmedIncludedAmountByContractAndAgent(
            @Param("contractId") Long contractId,
            @Param("paymentStage") PaymentStage paymentStage);
}
