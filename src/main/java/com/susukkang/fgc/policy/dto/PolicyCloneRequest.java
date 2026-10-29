package com.susukkang.fgc.policy.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** IF-API-81 승인본을 새 초안 판으로 복제한다. */
@Schema(description = "정책 새 판의 적용기간과 복제 사유")
public record PolicyCloneRequest(
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @NotBlank @Size(max = 2000) String reason
) {
    @AssertTrue(message = "종료일은 시작일 이상이어야 합니다.")
    @JsonIgnore
    public boolean isValidPeriod() {
        return effectiveFrom == null || effectiveTo == null || !effectiveTo.isBefore(effectiveFrom);
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("허용되지 않은 정책 복제 필드입니다: " + name);
    }
}
