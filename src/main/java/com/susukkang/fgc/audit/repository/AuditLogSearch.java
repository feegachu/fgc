package com.susukkang.fgc.audit.repository;

import com.susukkang.fgc.audit.dto.AuditLogRow;
import com.susukkang.fgc.audit.dto.AuditLogSearchCriteria;
import org.springframework.data.domain.Pageable;
import java.util.List;

/** 읽기 전용 저장소의 동적 검색 계약. */
public interface AuditLogSearch {
    /** 발생 시각·ID 내림차순으로 조회한다. Pageable은 범위에만 사용하며 unpaged는 전체를 반환한다. */
    List<AuditLogRow> selectAuditLogs(AuditLogSearchCriteria criteria, Pageable pageable);
    long countAuditLogs(AuditLogSearchCriteria criteria);
}
