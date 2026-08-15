package com.susukkang.fgc.journal.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 검증원장 목록 1행
 * journal_header + journal_line(차변/대변 합계) + insurance_contract(계약번호)
 * + app_user(작성자/기표자 login_id) 조인 투영. ValidationRunListRow와 같은 Row/Response 분리 관례.
 */
@Getter
@Setter
public class JournalListRow {
    private Long journalHeaderId;
    private String journalNo;
    private LocalDate journalDate;
    private String journalType;
    private String sourceEntityType;
    private String sourceEntityId;
    private Long contractId;
    private String contractNo;
    private String status;
    private BigDecimal debitTotal;
    private BigDecimal creditTotal;
    private Long reversalOfId;
    private String reversalOfJournalNo;
    private String createdBy;
    private String postedBy;
    private OffsetDateTime postedAt;
    private OffsetDateTime createdAt;
}
