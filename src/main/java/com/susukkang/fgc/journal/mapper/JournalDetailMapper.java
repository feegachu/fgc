package com.susukkang.fgc.journal.mapper;

import com.susukkang.fgc.journal.dto.JournalDetailHeaderRow;
import com.susukkang.fgc.journal.dto.JournalDetailLineRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 검증원장 상세 조회 전용 Mapper
 */
@Mapper
public interface JournalDetailMapper {

    /** journal_header_id 단건 조회. 없으면 null(Service가 COMMON_004로 변환한다). */
    JournalDetailHeaderRow findHeaderById(@Param("journalHeaderId") Long journalHeaderId);

    /** 그 헤더에 속한 분개 라인 전체를 line_no 오름차순으로 조회한다. */
    List<JournalDetailLineRow> findLinesByHeaderId(@Param("journalHeaderId") Long journalHeaderId);
}
