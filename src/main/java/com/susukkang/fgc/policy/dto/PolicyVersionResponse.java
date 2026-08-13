package com.susukkang.fgc.policy.dto;

import com.susukkang.fgc.common.code.PolicySourceClass;
import com.susukkang.fgc.common.code.PolicyStatus;
import com.susukkang.fgc.common.code.PolicyType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * IF-API-09 정책 버전 목록 1행 / IF-API-10 상세 헤더 (FGC-FUN-012·013)
 */
@Schema(description = "정책 버전")
public record PolicyVersionResponse(
        @Schema(description = "정책 버전 ID", example = "1")
        Long policyVersionId,
        @Schema(description = "정책 코드", example = "REG-CAP-GA-2026-V1")
        String policyCode,
        @Schema(description = "정책 이름", example = "GA→설계사 1,200% 한도 규제(2026.7.1 시행)")
        String policyName,
        @Schema(description = "정책 유형")
        PolicyType policyType,
        @Schema(description = "정책 유형 한글 라벨", example = "1,200% 한도")
        String policyTypeLabel,
        @Schema(description = "출처분류 — PROJECT_ASSUMPTION 은 근거 없는 프로젝트 가정값")
        PolicySourceClass sourceClass,
        @Schema(description = "출처분류 한글 라벨", example = "규제")
        String sourceClassLabel,
        @Schema(description = "버전 번호", example = "1")
        Integer versionNo,
        @Schema(description = "정책 상태")
        PolicyStatus status,
        @Schema(description = "정책 상태 한글 라벨", example = "적용중")
        String statusLabel,
        @Schema(description = "적용 시작일", example = "2026-07-01")
        LocalDate effectiveFrom,
        @Schema(description = "적용 종료일. 없으면 null(계속 적용)", example = "2026-12-31", nullable = true)
        LocalDate effectiveTo,
        @Schema(description = "규제 근거 코드 목록(REG-xx). 비어 있으면 화면에서 '근거 미기재' 표기")
        List<String> regulationRefs,
        @Schema(description = "작성자 로그인 ID", nullable = true)
        String createdBy,
        @Schema(description = "승인자 로그인 ID", nullable = true)
        String approvedBy,
        @Schema(description = "승인 일시", nullable = true)
        OffsetDateTime approvedAt
) {
    public static PolicyVersionResponse from(PolicyVersionRow row) {
        PolicyType type = PolicyType.valueOf(row.getPolicyType());
        PolicySourceClass sourceClass = PolicySourceClass.valueOf(row.getSourceClass());
        PolicyStatus status = PolicyStatus.valueOf(row.getStatus());
        return new PolicyVersionResponse(
                row.getPolicyVersionId(), row.getPolicyCode(), row.getPolicyName(),
                type, type.label(), sourceClass, sourceClass.label(),
                row.getVersionNo(), status, status.label(),
                row.getEffectiveFrom(), row.getEffectiveTo(), row.getRegulationRefs(),
                row.getCreatedBy(), row.getApprovedBy(), row.getApprovedAt());
    }
}
