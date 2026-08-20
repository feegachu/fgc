package com.susukkang.fgc.dashboard.dto;

import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ExceptionType;

import java.time.OffsetDateTime;

/**
 * FGC-UI-DASH-W01 "최근 예외" 응답 1행 (IF-API-03).
 * SIR-008 — 코드값은 영문 코드와 한글 라벨을 함께 내려준다. {@link RecentExceptionRow}(Mapper 원본)를
 * 그대로 노출하지 않고 이 응답 DTO로 감싸, {@code ExceptionCaseResponseDTO.from()}과 같은 패턴으로
 * 서버가 라벨을 만들어 붙인다.
 */
public record RecentExceptionResponse(
        Long exceptionCaseId,
        String exceptionType,
        String exceptionTypeLabel,
        String severity,
        String severityLabel,
        String contractNo,
        String title,
        String status,
        String statusLabel,
        OffsetDateTime createdAt
) {
    public static RecentExceptionResponse from(RecentExceptionRow row) {
        return new RecentExceptionResponse(
                row.exceptionCaseId(),
                row.exceptionType(),
                ExceptionType.valueOf(row.exceptionType()).label(),
                row.severity(),
                ExceptionSeverity.valueOf(row.severity()).label(),
                row.contractNo(),
                row.title(),
                row.status(),
                ExceptionStatus.valueOf(row.status()).label(),
                row.createdAt()
        );
    }
}
