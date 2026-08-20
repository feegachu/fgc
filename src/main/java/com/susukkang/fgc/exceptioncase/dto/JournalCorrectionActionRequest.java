package com.susukkang.fgc.exceptioncase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : IF-API-44A 원장 정정 예외의 역분개·재기표 실행 요청
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
public record JournalCorrectionActionRequest(
        @NotBlank @Size(max = 1000) String reason,
        @Size(max = 500) String evidenceRef,
        @NotNull LocalDate journalDate,
        @NotBlank @Size(max = 1000) String description,
        @NotEmpty List<@NotNull @Valid JournalCorrectionActionLineRequest> lines
) {
}
