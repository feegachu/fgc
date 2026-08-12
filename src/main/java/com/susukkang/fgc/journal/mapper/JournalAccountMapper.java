package com.susukkang.fgc.journal.mapper;

import com.susukkang.fgc.journal.dto.JournalAccountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;


@Mapper
public interface JournalAccountMapper {
    JournalAccountRow findActiveByCode(@Param("accountCode") String accountCode);
}
