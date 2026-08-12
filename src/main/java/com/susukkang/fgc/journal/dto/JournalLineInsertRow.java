package com.susukkang.fgc.journal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * journal_line 1행 INSERT 파라미터(#93). journalAccountId는 JournalAccountCode(#85)를
 * JournalAccountMapper.findActiveByCode로 조회해 얻은 실제 FK 값이다 — JournalLineDraft가
 * 들고 있던 enum 그대로는 저장할 수 없다(계정 존재·활성 여부를 이 조회가 같이 검증한다).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JournalLineInsertRow {
    private Long journalLineId;
    private Long journalHeaderId;
    private Integer lineNo;
    private Long journalAccountId;
    private BigDecimal debitAmount;
    private BigDecimal creditAmount;
    private Long contractId;
    private Long agentId;
    private String paymentStage;
    private Long commissionItemId;
    private String memo;
}
