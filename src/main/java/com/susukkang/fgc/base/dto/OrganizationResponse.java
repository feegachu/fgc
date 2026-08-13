package com.susukkang.fgc.base.dto;

import com.susukkang.fgc.base.code.OrganizationType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "GA·조직 기준정보")
public record OrganizationResponse(
        @Schema(description = "조직 ID", example = "11")
        Long organizationId,
        @Schema(description = "조직 코드", example = "FGC-BR-SEOUL")
        String organizationCode,
        @Schema(description = "조직명", example = "서울지사")
        String organizationName,
        @Schema(description = "조직 유형 코드", allowableValues = {"GA", "HQ", "DIVISION", "BRANCH", "TEAM"})
        String organizationType,
        @Schema(description = "조직 유형 한글명", example = "지사")
        String organizationTypeLabel,
        @Schema(description = "상위 조직 ID", example = "1", nullable = true)
        Long parentId,
        @Schema(description = "상위 조직명", example = "FGC 대표 GA", nullable = true)
        String parentName,
        @Schema(description = "적용 시작일", example = "2026-01-01")
        LocalDate effectiveFrom,
        @Schema(description = "적용 종료일. 종료일이 없으면 null", example = "2026-12-31", nullable = true)
        LocalDate effectiveTo,
        @Schema(description = "활성 여부. false인 조직은 조회되지만 선택할 수 없음", example = "true")
        boolean activeYn
) {
    public static OrganizationResponse from(OrganizationRow row) {
        OrganizationType type = OrganizationType.valueOf(row.organizationType());
        return new OrganizationResponse(
                row.organizationId(),
                row.organizationCode(),
                row.organizationName(),
                type.name(),
                type.label(),
                row.parentId(),
                row.parentName(),
                row.effectiveFrom(),
                row.effectiveTo(),
                row.activeYn()
        );
    }
}
