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
    @Size(max = 40)
    private String reason;
}