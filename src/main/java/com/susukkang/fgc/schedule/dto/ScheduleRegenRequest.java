package com.susukkang.fgc.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 설명 : 현재 스케줄 재 생성시 사유와 함께 입력받는 Request
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-11
 */
@Getter
@NoArgsConstructor
public class ScheduleRegenRequest {
    @NotBlank
    @Size(min = 1, max = 40)
    @io.swagger.v3.oas.annotations.media.Schema(minLength = 1, description = "생성 사유. 공백만 있는 값은 허용하지 않음")
    private String reason;
    private com.susukkang.fgc.schedule.code.ScheduleRegime regime;
    @jakarta.validation.constraints.Positive
    @io.swagger.v3.oas.annotations.media.Schema(description = "명시적 FOUR_YEAR_2027/SEVEN_YEAR_2029 생성 시 필수인 승인·활성 정책 ID")
    private Long policyVersionId;
    @Size(max = 100)
    @io.swagger.v3.oas.annotations.media.Schema(description = "명시적 4년·7년 생성 시 필수인 previewContext 원천 토큰. 불일치 시 409")
    private String expectedSourceVersion;
}