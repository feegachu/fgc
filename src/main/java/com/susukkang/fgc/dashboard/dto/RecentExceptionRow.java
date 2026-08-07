package com.susukkang.fgc.dashboard.dto;

import java.time.OffsetDateTime;

/**
 * FGC-UI-DASH-W01 "최근 예외 5건" 목록 1행
 * exception_case + insurance_contract(contract_no) join 결과
 */
public record RecentExceptionRow(
        Long exceptionCaseId,
        String exceptionType,
        String severity,
        String contractNo,
        String title,
        String status,
        OffsetDateTime createdAt
) {
}
