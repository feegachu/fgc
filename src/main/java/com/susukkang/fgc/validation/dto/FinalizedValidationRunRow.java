package com.susukkang.fgc.validation.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 확정 결과 재조회용 DB 행. finalizedBy는 화면 표시용 login_id다. */
@Getter
@Setter
public class FinalizedValidationRunRow {
    private Long validationRunId;
    private String status;
    private OffsetDateTime finalizedAt;
    private String finalizedBy;
    private String finalizeIdempotencyKey;
}
