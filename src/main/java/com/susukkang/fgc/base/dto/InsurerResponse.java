package com.susukkang.fgc.base.dto;

import com.susukkang.fgc.base.code.InsurerType;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "보험회사(원수사) 기준정보")
public record InsurerResponse(
        @Schema(description = "보험회사 ID", example = "1")
        Long insurerId,
        @Schema(description = "보험회사 코드", example = "FGL01")
        String insurerCode,
        @Schema(description = "보험회사명", example = "미래가상생명")
        String insurerName,
        @Schema(description = "보험회사 유형 코드", allowableValues = {"LIFE", "NON_LIFE"})
        String insurerType,
        @Schema(description = "보험회사 유형 한글명", example = "생명보험")
        String insurerTypeLabel,
        @Schema(description = "활성 여부. false인 보험회사는 조회되지만 신규 입력에서 선택할 수 없음", example = "true")
        boolean activeYn
) {
    public static InsurerResponse from(InsurerRow row) {
        InsurerType type = InsurerType.valueOf(row.insurerType());
        return new InsurerResponse(
                row.insurerId(),
                row.insurerCode(),
                row.insurerName(),
                type.name(),
                type.label(),
                row.activeYn()
        );
    }
}
