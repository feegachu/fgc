package com.susukkang.fgc.journal.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.journal.domain.JournalAccountCode;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * journal_line 한 행의 초안. debitAmount/creditAmount 중 하나만 0보다 크고 나머지는
 * 0이어야 한다(ck_journal_line_one_side, V1__baseline_v2_1_2.sql:1248-1251) — 이 DTO를
 * 만드는 서비스가 그 규칙을 지켜야 하며, 이 클래스 자체는 값을 강제하지 않는다.
 *
 * journalAccountId(FK)는 포함하지 않는다 — 계정과목을 코드(accountCode)가 아니라 DB
 * 식별자로 다루는 건 영속화 단계(#93, JournalPersistenceService)의 몫이다.
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
