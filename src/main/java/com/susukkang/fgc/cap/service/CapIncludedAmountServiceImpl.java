package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapIncludedAmountSummary;
import com.susukkang.fgc.cap.mapper.CapIncludedAmountMapper;
import com.susukkang.fgc.common.code.PaymentStage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 설명 : FUN-031 계약별 한도 산입 지급액을 합산하는 서비스 구현체
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CapIncludedAmountServiceImpl implements CapIncludedAmountService {

    private final CapIncludedAmountMapper capIncludedAmountMapper;

    /**
     * 설명 : 지급 확정 전 기존 확정액과 현재 지급 건의 산입액을 합산한다
     *
     * @param contractId 계약 ID
     * @param transactionId 현재 확정할 지급 건 ID
     * @param paymentStage 지급 단계
     * @return 계약별 한도 산입액 합계
     * @author hjKang
     * @since 2026-08-12
     */
    @Override
    public BigDecimal calculatePreConfirmAmount(Long contractId, Long transactionId, PaymentStage paymentStage) {
        List<CapIncludedAmountSummary> summaries =
                capIncludedAmountMapper.sumIncludedAmountByContractAndAgent(contractId, transactionId, paymentStage);
        return sumIncludedAmounts(summaries);
    }

    /**
     * 설명 : 월 검증 실행 시 확정 지급 건의 계약별 산입액을 전체 재합산한다
     *
     * @param contractId 계약 ID
     * @param paymentStage 지급 단계
     * @return 계약별 확정 한도 산입액 합계
     * @author hjKang
     * @since 2026-08-12
     */
    @Override
    public BigDecimal recalculateTotalAmount(Long contractId, PaymentStage paymentStage) {
        List<CapIncludedAmountSummary> summaries =
                capIncludedAmountMapper.sumConfirmedIncludedAmountByContractAndAgent(contractId, paymentStage);
        return sumIncludedAmounts(summaries);
    }

    /**
     * 설명 : 설계사별 귀속액을 합쳐 계약 전체 규제 판정 금액을 계산한다
     *
     * @param summaries 계약·설계사별 한도 산입액 목록
     * @return 계약 전체 한도 산입액 합계
     * @author hjKang
     * @since 2026-08-12
     */
    private BigDecimal sumIncludedAmounts(List<CapIncludedAmountSummary> summaries) {
        return summaries
                .stream()
                .map(CapIncludedAmountSummary::getIncludedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
