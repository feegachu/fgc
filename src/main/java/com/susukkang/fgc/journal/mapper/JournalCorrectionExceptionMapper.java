package com.susukkang.fgc.journal.mapper;

import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionInsertCommand;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 설명 : IF-API-36A 원장 정정 예외의 멱등 생성 Mapper
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
@Mapper
public interface JournalCorrectionExceptionMapper {

    int insertCase(JournalCorrectionExceptionInsertCommand command);

    JournalCorrectionExceptionRow findByExceptionKey(
            @Param("exceptionKey") String exceptionKey);

    int insertInitialAction(JournalCorrectionExceptionInsertCommand command);
}
