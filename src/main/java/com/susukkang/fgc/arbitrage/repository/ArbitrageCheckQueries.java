package com.susukkang.fgc.arbitrage.repository;

import com.susukkang.fgc.arbitrage.dto.*;
import com.susukkang.fgc.common.code.PaymentStage;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;

public interface ArbitrageCheckQueries {
    //검색조건에 해당하는 차익거래 검증 결과 List를 select한다
    List<ArbitrageCheckView> selectByCondition(
            ArbitrageCheckSearchCondition condition,
            int offset,
            int size);
    //검색조건에 해당하는 차익거래 검증 결과의 개수를 select한다
    ArbitrageCheckSummary arbitrageCheckSummary(
            ArbitrageCheckSearchCondition condition);

    // 검색 조건에 해당하는 차익거래 검증 결과의 전체 건수를 조회한다.
    long countByCondition(
            ArbitrageCheckSearchCondition condition);

    // 계약과 기준일에 해당하는 계약 정보 및 최신 금융 스냅샷을 조회한다
    ArbitrageCalculationSource selectCalculationSource(
            Long contractId,
            LocalDate asOfDate);

    // 계약에 귀속된 기준일 이하 확정 수수료 순액을 조회한다
    ConfirmedCommissionSummary sumConfirmedCommissionAmount(
            Long contractId,
            PaymentStage paymentStage,
            LocalDate asOfDate);

    // 계약의 활성 운영 스케줄에 남은 지급예정 수수료를 조회한다
    BigDecimal sumPlannedCommissionAmount(
            Long contractId,
            PaymentStage paymentStage);

    // 계약 상품과 계약차월에 적용 가능한 예상 환급률표 후보를 조회한다
    List<ArbitrageRefundRateCandidate> selectRefundRateCandidates(
            ArbitrageCalculationSource source,
            LocalDate asOfDate);

    // 차익거래 검증 결과를 저장한다
    int insertArbitrageCheck(ArbitrageCheckInsertDTO row);

    // 계약의 기준일별 차익거래 검증 결과 시계열을 조회한다
    List<ArbitrageCheckView> selectByContractId(
            Long contractId,
            PaymentStage paymentStage);

}
