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
        String sourceEntityId,
        Long capCheckId
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

    /**
     * 계산근거(CAP-W02) 링크 — #331.
     *
     * CapCheckMapper 의 목록 두 곳은 확정 거절된 DRAFT 후보의 cap_check 를 제외하므로
     * (latestScopedCapChecks · findLatestByContractAndStage 의 candidate_transaction_id 조건)
     * CAP-W01·CONT-W02 에서는 그 판정에 도달할 수 없다. 주석이 정한 대로 예외함이 그 자리다.
     * IF-API-31(findById)은 제외 조건이 없어 팝업 자체는 정상 동작한다.
     *
     * 실시간 경로(FUN-034)는 cap_check_id 컬럼을, 배치 경로는 source_entity 를 쓴다 —
     * 컬럼을 우선 보고 없으면 source_entity 로 물러선다.
     */
    public String capBasisLink() {
        if (capCheckId != null) {
            return "/cap-checks?capCheckId=" + capCheckId;
        }
        if ("CAP_CHECK".equals(sourceEntityType) && sourceEntityId != null && !sourceEntityId.isBlank()) {
            return "/cap-checks?capCheckId=" + sourceEntityId;
        }
        return null;
    }
}
