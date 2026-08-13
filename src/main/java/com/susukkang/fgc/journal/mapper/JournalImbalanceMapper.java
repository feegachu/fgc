package com.susukkang.fgc.journal.mapper;

import com.susukkang.fgc.journal.dto.JournalBalanceSummary;
import com.susukkang.fgc.journal.dto.LedgerImbalanceRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface JournalImbalanceMapper {

    JournalBalanceSummary findBalanceSummary(@Param("journalHeaderId") Long journalHeaderId);

    List<LedgerImbalanceRow> findImbalances(@Param("validationRunId") Long validationRunId);

    long countImbalances(@Param("validationRunId") Long validationRunId);

    long countJournals(@Param("validationRunId") Long validationRunId);
}
