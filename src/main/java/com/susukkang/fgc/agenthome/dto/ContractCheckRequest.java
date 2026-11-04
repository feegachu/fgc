package com.susukkang.fgc.agenthome.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** IF-API-74A 요청. FOLLOW_UP 은 nextCheckDate 필수, CLOSED 는 null 이어야 한다(서비스에서 검사). */
public record ContractCheckRequest(
        @NotNull Long contractId,
        @NotBlank @Pattern(regexp = "CLOSED|FOLLOW_UP") String result,
        LocalDate nextCheckDate,
        @Size(max = 1000) String memo,
        @NotBlank @Size(max = 128) String expectedContextVersion) {
}
