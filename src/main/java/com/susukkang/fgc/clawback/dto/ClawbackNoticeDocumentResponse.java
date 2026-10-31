package com.susukkang.fgc.clawback.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/** IF-API-73·86 DocumentVersion. 저장 위치(storage_key)는 응답하지 않고 권한을 다시 검사하는 다운로드 경로만 준다. */
public record ClawbackNoticeDocumentResponse(
        String documentNo,
        Integer version,
        Integer calculationVersion,
        String sha256,
        OffsetDateTime createdAt,
        Long createdBy,
        @Schema(description = "IF-API-67A 다운로드 경로. 호출 시 권한을 다시 검사한다")
        String downloadPath
) { }
