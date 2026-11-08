package com.susukkang.fgc.journal.dto;

import com.susukkang.fgc.common.web.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record JournalExplorer(PageResponse<Contract> contracts, PageResponse<Entry> journals, Long selectedContractId, Totals totals) {
    public JournalExplorer(PageResponse<Contract> contracts, PageResponse<Entry> journals, Long selectedContractId) {
        this(contracts,journals,selectedContractId,null);
    }
    public record Filter(LocalDate from, LocalDate to, String type, String account, String contractNo, String status) { }
    public record Contract(long contractId, String contractNo, long journalCount, BigDecimal debitTotal, BigDecimal creditTotal, String agentName, String insurerName) { }
    public record Totals(long journalCount, long lineCount, BigDecimal debitTotal, BigDecimal creditTotal) { }
    public record Line(int lineNo, String accountCode, String accountName, BigDecimal debitAmount, BigDecimal creditAmount, String memo) { }
    public record Entry(long journalHeaderId, String journalNo, LocalDate journalDate, String journalType, String status,
                        Long validationRunId, String description, BigDecimal debitTotal, BigDecimal creditTotal, List<Line> lines) { }
}
