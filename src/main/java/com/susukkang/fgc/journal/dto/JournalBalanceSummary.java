package com.susukkang.fgc.journal.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 분개 헤더 하나의 차변·대변 합계
 * journal_line을 journal_header_id로 묶어 SUM한 결과이며,
 * 균형/불균형 여부와 무관하게(즉 imbalance가 아니어도) 계산 가능
 */
@Getter
@Setter
public class JournalBalanceSummary {
    private Long journalHeaderId;
    private BigDecimal debitTotal;
    private BigDecimal creditTotal;

    /**
     * 이슈 #98 균형 조건: 차변>0, 대변>0, 차변==대변을 모두 만족해야 한다.
     * debitTotal/creditTotal 중 하나라도 null이면(값을 안 채우고 호출한 경우) 0으로 취급한다 —
     * summarize()의 null 헤더 처리와 동일하게 "미균형"으로만 판정하고 NPE는 내지 않는다.
     */
    public boolean isBalanced() {
        BigDecimal debit = nullToZero(debitTotal);
        BigDecimal credit = nullToZero(creditTotal);
        return debit.compareTo(BigDecimal.ZERO) > 0
                && credit.compareTo(BigDecimal.ZERO) > 0
                && debit.compareTo(credit) == 0;
    }

    public BigDecimal differenceAmount() {
        return nullToZero(debitTotal).subtract(nullToZero(creditTotal)).abs();
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
