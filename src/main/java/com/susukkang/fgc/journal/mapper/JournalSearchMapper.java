package com.susukkang.fgc.journal.mapper;

import com.susukkang.fgc.journal.dto.JournalListRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 검증원장 목록 조회 전용 Mapper
 * journal_header 단위로 페이징 목록을 돌려준다.
 */
@Mapper
public interface JournalSearchMapper {

    /**
     * 검색조건에 맞는 검증원장 목록을 journal_date DESC, journal_header_id DESC 순으로
     * offset/limit 페이징해 돌려준다. from/to/journalType/accountCode/contractId/status가
     * null이면 그 조건은 걸지 않는다(전체).
     */
    List<JournalListRow> search(@Param("from") LocalDate from,
                                 @Param("to") LocalDate to,
                                 @Param("journalType") String journalType,
                                 @Param("accountCode") String accountCode,
                                 @Param("contractId") Long contractId,
                                 @Param("status") String status,
                                 @Param("offset") int offset,
                                 @Param("limit") int limit);

    /** search와 같은 조건의 전체 건수 — 페이징 totalElements 계산용. */
    long count(@Param("from") LocalDate from,
               @Param("to") LocalDate to,
               @Param("journalType") String journalType,
               @Param("accountCode") String accountCode,
               @Param("contractId") Long contractId,
               @Param("status") String status);
}
