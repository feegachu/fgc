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

    /**
     * "참조" 컬럼의 원천 화면 링크 — 화면정의서 :1349 "만들 때 주의":
     * 참조 컬럼은 원천 화면으로 바로 가는 링크다(없으면 담당자가 원인을 못 찾는다).
     *
     * 화면 라우트가 있는 원천 유형만 매핑한다.
     * 모르는 유형은 null — 화면이 링크 없이 텍스트로만 표시한다.
     */
    public String sourceLink() {
        if (sourceEntityType == null || sourceEntityId == null || sourceEntityId.isBlank()) {
            return null;
        }
        return switch (sourceEntityType) {
            case "INSURANCE_CONTRACT" -> "/contracts/" + sourceEntityId;
            case "ARBITRAGE_CHECK" -> "/arbitrage-checks";
            case "COMMISSION_TRANSACTION" -> "/transactions/new?id=" + sourceEntityId;
            case "SCHEDULE_HEADER" -> "/schedules/" + sourceEntityId;
            default -> null;
        };
    }
}
