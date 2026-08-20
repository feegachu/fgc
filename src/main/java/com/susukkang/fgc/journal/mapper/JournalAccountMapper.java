package com.susukkang.fgc.journal.mapper;

import com.susukkang.fgc.journal.dto.JournalAccountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 설명 : 활성 원장 계정과목 조회 Mapper
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
@Mapper
public interface JournalAccountMapper {
    JournalAccountRow findActiveByCode(@Param("accountCode") String accountCode);

    List<JournalAccountRow> findAllActive();
}
