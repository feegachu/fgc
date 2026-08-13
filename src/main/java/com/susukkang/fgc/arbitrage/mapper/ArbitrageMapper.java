package com.susukkang.fgc.arbitrage.mapper;

import com.susukkang.fgc.arbitrage.dto.*;
import com.susukkang.fgc.common.code.PaymentStage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 차익거래 검증 결과를 가지고 CRUD 작업을 하는 Mapper
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Mapper
public interface ArbitrageMapper {
    //검색조건에 해당하는 차익거래 검증 결과 List를 select한다
    List<ArbitrageCheckView> selectByCondition(
            @Param("condition") ArbitrageCheckSearchCondition condition,
            @Param("offset") int offset,
            @Param("size") int size);
    //검색조건에 해당하는 차익거래 검증 결과의 개수를 select한다
    ArbitrageCheckSummary arbitrageCheckSummary(
            @Param("condition") ArbitrageCheckSearchCondition condition);

    // 계약과 기준일에 해당하는 계약 정보 및 최신 금융 스냅샷을 조회한다
    ArbitrageCalculationSource selectCalculationSource(
            @Param("contractId") Long contractId,
            @Param("asOfDate") LocalDate asOfDate);

    // 계약에 귀속된 기준일 이하 확정 수수료 순액을 조회한다
    ConfirmedCommissionSummary sumConfirmedCommissionAmount(
            @Param("contractId") Long contractId,
            @Param("paymentStage") PaymentStage paymentStage,
            @Param("asOfDate") LocalDate asOfDate);

    // 계약의 활성 운영 스케줄에 남은 지급예정 수수료를 조회한다
    BigDecimal sumPlannedCommissionAmount(
            @Param("contractId") Long contractId,
            @Param("paymentStage") PaymentStage paymentStage);

    // 계약 상품과 계약차월에 적용 가능한 예상 환급률표 후보를 조회한다
    List<ArbitrageRefundRateCandidate> selectRefundRateCandidates(
            @Param("source") ArbitrageCalculationSource source,
            @Param("asOfDate") LocalDate asOfDate);

    // 차익거래 검증 결과를 저장한다
    int insertArbitrageCheck(ArbitrageCheckInsertDTO row);

    // 계약의 기준일별 차익거래 검증 결과 시계열을 조회한다
    List<ArbitrageCheckView> selectByContractId(
            @Param("contractId") Long contractId,
            @Param("paymentStage") PaymentStage paymentStage);

    // 월 검증 실행에서 선별된 계약 ID 목록을 조회한다
    List<Long> selectSelectedContractIds(@Param("validationRunId") Long validationRunId);
}
