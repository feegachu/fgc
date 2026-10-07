package com.susukkang.fgc.audit.service;

import com.susukkang.fgc.audit.dto.AuditLogDetailResponse;
import com.susukkang.fgc.audit.dto.AuditLogOptionsResponse;
import com.susukkang.fgc.audit.dto.AuditLogResponse;
import com.susukkang.fgc.audit.dto.AuditUserRow;
import com.susukkang.fgc.common.web.PageResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AuditLogQueryService {

    PageResponse<AuditLogResponse> search(
            String entityType,
            String entityId,
            Long userId,
            String action,
            LocalDate from,
            LocalDate to,
            int page,
            int size
    );

    /** AUDT-W01 행위 종류 필터 선택지. */
    List<String> actionCodes();

    /** AUDT-W01 대상 종류 필터 선택지. */
    List<String> entityTypes();

    /** AUDT-W01 행위자 필터 선택지. */
    List<AuditUserRow> auditUsers();

    /** AUDT-W01 필터 선택지 한 번에(#407). */
    AuditLogOptionsResponse options();

    /** AUDT-W01 상세 — 감사행과 변경 내용 비교(#407). 없으면 empty. */
    Optional<AuditLogDetailResponse> detail(Long auditLogId);
}
