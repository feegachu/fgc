package com.susukkang.fgc.journal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** IF-API-36 역분개 요청. */
public record ReverseJournalRequest(
        @NotBlank @Size(max = 1000) String reason,
        @Size(max = 500) String evidenceRef
) {
}
