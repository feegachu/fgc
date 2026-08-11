package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapIncludedAmountSummary;
import com.susukkang.fgc.cap.mapper.CapIncludedAmountMapper;
import com.susukkang.fgc.common.code.PaymentStage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CapIncludedAmountServiceImpl implements CapIncludedAmountService {

    private final CapIncludedAmountMapper capIncludedAmountMapper;

    @Override
    public BigDecimal calculatePreConfirmAmount(Long contractId, Long transactionId, PaymentStage paymentStage) {
        List<CapIncludedAmountSummary> summaries = capIncludedAmountMapper.sumIncludedAmountByContractAndAgent(contractId, transactionId, paymentStage);
        return sumIncludedAmounts(summaries);
    }

    @Override
    public BigDecimal recalculateTotalAmount(Long contractId, PaymentStage paymentStage) {
        List<CapIncludedAmountSummary> summaries = capIncludedAmountMapper.sumConfirmedIncludedAmountByContractAndAgent(contractId, paymentStage);
        return sumIncludedAmounts(summaries);
    }

    // 설계사별 귀속액을 합쳐 계약 전체 규제 판정 금액을 계산
    private BigDecimal sumIncludedAmounts(List<CapIncludedAmountSummary> summaries) {
        return summaries.stream().map(CapIncludedAmountSummary::getIncludedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
