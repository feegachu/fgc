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

    /**
     * DRAFT -> POSTED 전이. guard_journal_header_write가 차변합계=대변합계>0을 강제하므로
     * 불균형 초안은 여기서 DataIntegrityViolationException으로 거절된다.
     *
     * @return 반영된 행 수. 0이면 이미 POSTED/REVERSED였거나(WHERE status='DRAFT' 불일치) 존재하지 않음.
     */
    int markPosted(@Param("journalHeaderId") Long journalHeaderId, @Param("postedBy") Long postedBy);
}
