package com.susukkang.fgc.journal.mapper;

import com.susukkang.fgc.journal.dto.JournalHeaderInsertRow;
import com.susukkang.fgc.journal.dto.JournalHeaderRow;
import com.susukkang.fgc.journal.dto.JournalLineInsertRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface JournalMapper {
    int insert(JournalHeaderInsertRow row);

    int insertLine(JournalLineInsertRow row);

    Integer findNextJournalSeq(@Param("year") String year, @Param("month") String month);

    JournalHeaderRow findBySourceKey(@Param("journalType") String journalType,
                                      @Param("sourceEntityType") String sourceEntityType,
                                      @Param("sourceEntityId") String sourceEntityId,
                                      @Param("revisionNo") int revisionNo);

    boolean existsPostedForSource(@Param("journalType") String journalType,
                                   @Param("sourceEntityType") String sourceEntityType,
                                   @Param("sourceEntityId") String sourceEntityId);
}
