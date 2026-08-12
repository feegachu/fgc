package com.susukkang.fgc.journal.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.journal.domain.JournalAccountCode;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * journal_line 한 행의 초안
 * debitAmount/creditAmount 중 하나만 0보다 크고 나머지는 0이어야 함
 */
@Getter
@Builder(toBuilder = true)
public class JournalLineDraft {
    private final int lineNo;
    private final JournalAccountCode accountCode;
    private final BigDecimal debitAmount;
    private final BigDecimal creditAmount;
    private final Long contractId;
    private final Long agentId;
    private final PaymentStage paymentStage;
    private final Long commissionItemId;
    private final String memo;
}
