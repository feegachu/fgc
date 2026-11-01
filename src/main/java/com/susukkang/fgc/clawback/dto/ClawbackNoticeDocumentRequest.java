package com.susukkang.fgc.clawback.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** IF-API-73 통지서 PDF 발행 요청. 금액·환수율은 받지 않고 지정한 계산판 snapshot으로만 만든다. */
public record ClawbackNoticeDocumentRequest(
        @Schema(description = "PDF 원본으로 쓸 계산판. case의 최신 판이 아니면 409 FGC-IMPL-STATE_CHANGED")
        @NotNull @Positive Integer calculationVersion,
        @Schema(description = "IF-API-84에서 받은 sourceVersion. 계산판·현재 원천과 다르면 409 FGC-IMPL-STATE_CHANGED")
        @NotBlank @Size(max = 128) String expectedSourceVersion
) { }
