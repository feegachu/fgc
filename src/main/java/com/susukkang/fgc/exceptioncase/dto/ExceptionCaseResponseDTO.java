package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ExceptionType;
import com.susukkang.fgc.common.util.DateUtil;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;

/**
 * 예외함 목록 1건과 오른쪽 처리 패널을 함께 구성하는 응답.
 * 목록을 받은 JavaScript가 행 선택 시 추가 API 없이 상세와 처리 이력을 표시한다.
 */
public record ExceptionCaseResponseDTO(
        Long exceptionCaseId,
        String exceptionKey,
        ExceptionType type,
        String reasonCode,
        ExceptionSeverity severity,
        ExceptionStatus status,
        String title,
        String description,
        String contractNo,
        String agentName,
        Long assignedTo,
        String assigneeLoginId,
        String sourceEntityType,
        String sourceEntityId,
        LocalDate validationMonth,
        Long firstDetectedRunId,
        Long lastDetectedRunId,
        OffsetDateTime firstDetectedAt,
        OffsetDateTime lastDetectedAt,
        int detectionCount,
        OffsetDateTime createdAt,
        List<ExceptionOccurrenceResponse> occurrences,
        List<ExceptionActionResponse> actions
) {
    public ExceptionCaseResponseDTO {
        occurrences = List.copyOf(occurrences);
        actions = List.copyOf(actions);
    }

    public static ExceptionCaseResponseDTO from(ExceptionCaseSearchRow row,
                                                 List<ExceptionOccurrenceResponse> occurrences,
                                                 List<ExceptionActionResponse> actions) {
        return new ExceptionCaseResponseDTO(
                row.exceptionCaseId(), row.exceptionKey(),
                ExceptionType.valueOf(row.exceptionType()),
                row.reasonCode(),
                ExceptionSeverity.valueOf(row.severity()),
                ExceptionStatus.valueOf(row.status()),
                row.title(), row.description(), row.contractNo(), row.agentName(),
                row.assignedTo(), row.assigneeLoginId(), row.sourceEntityType(),
                row.sourceEntityId(), row.validationMonth(), row.firstDetectedRunId(),
                row.lastDetectedRunId(), DateUtil.toSeoul(row.firstDetectedAt()),
                DateUtil.toSeoul(row.lastDetectedAt()), row.detectionCount(),
                DateUtil.toSeoul(row.createdAt()), occurrences, actions
        );
    }

    public String reasonLabel() {
        if (reasonCode == null || reasonCode.isBlank()) return "-";
        return switch (reasonCode) {
            case "CAP_RULE_MISMATCH" -> "한도 정책 불일치";
            case "CAP_LIMIT_VIOLATION" -> "1,200% 한도 초과";
            case "CAP_CALCULATION_REVIEW_REQUIRED" -> "한도 계산 검토 필요";
            case "ARBITRAGE_LIMIT_EXCEEDED" -> "차익거래 검토대상";
            case "FINANCIAL_SNAPSHOT_MISSING" -> "계약 금융 스냅샷 없음";
            case "REFUND_TABLE_MISSING" -> "환급률표 없음";
            case "PRODUCT_CODE_MISMATCH" -> "상품코드 불일치";
            case "POLICY_MISSING" -> "정책 없음";
            case "POLICY_DUPLICATE" -> "정책 중복";
            case "SCHEDULE_STRUCTURE_INVALID" -> "스케줄 구조 오류";
            case "SCHEDULE_POLICY_INVALID" -> "스케줄 정책 오류";
            case "CAP_CALCULATION_FAILED" -> "1,200% 계산 실패";
            case "ACTUAL_MISSING" -> "실제 지급 없음";
            case "EXPECTED_MISSING" -> "예상 지급 없음";
            case "DUPLICATE" -> "대사 대상 중복";
            case "AMOUNT_DIFFERENCE" -> "금액 불일치";
            default -> reasonCode;
        };
    }

    /** 예외의 원천 업무 화면이 제공되는 경우 바로 이동할 링크를 반환한다. */
    public String sourceLink() {
        return switch (sourceEntityType) {
            case "INSURANCE_CONTRACT" -> "/contracts/" + sourceEntityId;
            case "ARBITRAGE_CHECK" -> "/arbitrage-checks";
            case "COMMISSION_TRANSACTION" -> "/transactions";
            default -> null;
        };
    }
}
