package com.susukkang.fgc.exceptioncase.mapper;

import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionInsertCommand;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseActionTarget;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;

/** IF-API-44 예외 처리 이력 추가와 현재 상태 변경 Mapper. */
@Mapper
public interface ExceptionCaseActionMapper {

    ExceptionCaseActionTarget findByIdForUpdate(
            @Param("exceptionCaseId") Long exceptionCaseId);

    int findNextActionSeq(@Param("exceptionCaseId") Long exceptionCaseId);

    int insertAction(ExceptionActionInsertCommand command);

    int updateCaseAfterAction(
            @Param("exceptionCaseId") Long exceptionCaseId,
            @Param("status") ExceptionStatus status,
            @Param("assignedTo") Long assignedTo,
            @Param("resolvedAt") OffsetDateTime resolvedAt);
}
