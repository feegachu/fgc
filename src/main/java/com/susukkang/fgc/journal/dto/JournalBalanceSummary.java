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

    /** 이슈 #98 균형 조건: 차변>0, 대변>0, 차변==대변을 모두 만족해야 한다. */
    public boolean isBalanced() {
        return debitTotal.compareTo(BigDecimal.ZERO) > 0
                && creditTotal.compareTo(BigDecimal.ZERO) > 0
                && debitTotal.compareTo(creditTotal) == 0;
    }

    public BigDecimal differenceAmount() {
        return debitTotal.subtract(creditTotal).abs();
    }
}
