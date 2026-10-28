package com.susukkang.fgc.policy.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.susukkang.fgc.common.code.PolicyStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/** IF-API-75 상태 전이 요청. 승인자·시각은 요청에서 받지 않는다. */
@Schema(description = "정책 제출·승인·반려·활성화 요청")
public record PolicyActionRequest(
        @NotNull ActionType actionType,
        @NotBlank @Size(max = 2000) String reason,
        @NotNull PolicyStatus expectedStatus,
        @NotNull OffsetDateTime expectedUpdatedAt,
        @Schema(description = "APPROVE에서만 지정하는 승인 근거 문서 참조")
        @Size(max = 500) String evidenceRef
) {
    public PolicyActionRequest(ActionType actionType, String reason, PolicyStatus expectedStatus,
                               OffsetDateTime expectedUpdatedAt) {
        this(actionType, reason, expectedStatus, expectedUpdatedAt, null);
    }

    public enum ActionType { SUBMIT, APPROVE, REJECT, ACTIVATE }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("허용되지 않은 정책 동작 필드입니다: " + name);
    }
}
