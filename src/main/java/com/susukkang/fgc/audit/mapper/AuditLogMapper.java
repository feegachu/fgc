package com.susukkang.fgc.audit.mapper;

import com.susukkang.fgc.audit.dto.AuditLogInsertRow;
import org.apache.ibatis.annotations.Mapper;

// FUN-001 개발 순서 9

@Mapper
public interface AuditLogMapper {

    /** 감사로그 1건 기록. audit_log는 INSERT 전용이라 수정·삭제 메서드는 두지 않는다. */
    int insert(AuditLogInsertRow row);
}
