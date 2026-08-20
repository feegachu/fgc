package com.susukkang.fgc.journal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 설명 : IF-API-36A 원장 정정 예외 생성 요청
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
public record JournalCorrectionExceptionRequest(
        @NotBlank @Size(max = 1000) String reason,
        @Size(max = 500) String evidenceRef
) {
}
