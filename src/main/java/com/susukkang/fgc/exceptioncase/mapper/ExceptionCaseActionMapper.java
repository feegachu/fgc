package com.susukkang.fgc.exceptioncase.mapper;

import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionInsertCommand;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseActionTarget;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionExceptionTarget;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;

/**
 * 설명 : IF-API-44·44A 예외 처리 이력과 현재 상태 변경 Mapper
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
@Mapper
public interface ExceptionCaseActionMapper {

    ExceptionCaseActionTarget findByIdForUpdate(
            @Param("exceptionCaseId") Long exceptionCaseId);

    JournalCorrectionExceptionTarget findJournalCorrectionTargetForUpdate(
            @Param("exceptionCaseId") Long exceptionCaseId);

    int findNextActionSeq(@Param("exceptionCaseId") Long exceptionCaseId);

    int insertAction(ExceptionActionInsertCommand command);

    int updateCaseAfterAction(
            @Param("exceptionCaseId") Long exceptionCaseId,
            @Param("status") ExceptionStatus status,
            @Param("assignedTo") Long assignedTo,
            @Param("resolvedAt") OffsetDateTime resolvedAt);
}
