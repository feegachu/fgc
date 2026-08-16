package com.susukkang.fgc.audit.mapper;

import com.susukkang.fgc.audit.dto.AuditLogRow;
import com.susukkang.fgc.audit.dto.AuditLogSearchCriteria;
import com.susukkang.fgc.audit.dto.AuditUserRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * FUN-061 감사로그 조회 전용 매퍼.
 * INSERT 전용 {@link AuditLogMapper} 와 분리해 append-only 계약을 매퍼 단위로 유지한다.
 */
@Mapper
public interface AuditLogQueryMapper {

    List<AuditLogRow> selectAuditLogs(
            @Param("c") AuditLogSearchCriteria criteria,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    long countAuditLogs(@Param("c") AuditLogSearchCriteria criteria);

    /** AUDT-W01 행위 종류 필터 선택지. */
    List<String> selectDistinctActionCodes();

    /** AUDT-W01 대상 종류 필터 선택지. */
    List<String> selectDistinctEntityTypes();

    /** AUDT-W01 행위자 필터 선택지 — 감사행을 남긴 사용자만. */
    List<AuditUserRow> selectAuditUsers();
}
