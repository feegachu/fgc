package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ExceptionType;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 예외함 목록 1건과 오른쪽 처리 패널을 함께 구성하는 응답.
 * 목록을 받은 JavaScript가 행 선택 시 추가 API 없이 상세와 처리 이력을 표시한다.
 */
public record ExceptionCaseResponseDTO(
        Long exceptionCaseId,
        String exceptionKey,
        ExceptionType type,
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
        OffsetDateTime createdAt,
        List<ExceptionActionResponse> actions
) {
    public ExceptionCaseResponseDTO {
        actions = List.copyOf(actions);
    }

    public static ExceptionCaseResponseDTO from(ExceptionCaseSearchRow row,
                                                 List<ExceptionActionResponse> actions) {
        return new ExceptionCaseResponseDTO(
                row.exceptionCaseId(), row.exceptionKey(),
                ExceptionType.valueOf(row.exceptionType()),
                ExceptionSeverity.valueOf(row.severity()),
                ExceptionStatus.valueOf(row.status()),
                row.title(), row.description(), row.contractNo(), row.agentName(),
                row.assignedTo(), row.assigneeLoginId(), row.sourceEntityType(),
                row.sourceEntityId(), row.createdAt(), actions
        );
    }
}
