package com.susukkang.fgc.exceptioncase.dto;

import java.time.OffsetDateTime;

/**
 * FGC-UI-EXCP-W01 예외 목록 1행
 * exception_case + insurance_contract(contract_no) + app_user(login_id) join 결과
 */
public record ExceptionCaseListRow(
        Long exceptionCaseId,
        String exceptionType,
        String severity,
        String contractNo,
        String title,
        String status,
        String assignedTo,
        OffsetDateTime createdAt,
        String sourceEntityType,
        String sourceEntityId
) {
}
