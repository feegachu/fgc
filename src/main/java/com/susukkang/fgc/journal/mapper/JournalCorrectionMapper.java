package com.susukkang.fgc.journal.mapper;

import com.susukkang.fgc.journal.dto.JournalCorrectionGroupInsertRow;
import com.susukkang.fgc.journal.dto.JournalCorrectionHeaderRow;
import com.susukkang.fgc.journal.dto.JournalCorrectionLineRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface JournalCorrectionMapper {

    JournalCorrectionHeaderRow findHeaderForUpdate(@Param("journalHeaderId") Long journalHeaderId);

    List<JournalCorrectionLineRow> findLines(@Param("journalHeaderId") Long journalHeaderId);

    int insertGroup(JournalCorrectionGroupInsertRow row);

    /** 월별 journal_no 채번을 현재 트랜잭션 안에서 직렬화한다. */
    int lockJournalNumbering(@Param("lockKey") String lockKey);

    int markPosted(@Param("journalHeaderId") Long journalHeaderId,
                   @Param("postedBy") Long postedBy);

    int markReversed(@Param("journalHeaderId") Long journalHeaderId);
}
