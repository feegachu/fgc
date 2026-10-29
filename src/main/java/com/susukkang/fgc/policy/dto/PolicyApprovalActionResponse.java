package com.susukkang.fgc.policy.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/** 기존 시드의 REVIEW·RETIRE 이력도 원래 코드 그대로 조회한다. */
@Schema(description = "정책 승인 동작 이력")
public record PolicyApprovalActionResponse(
        Long policyApprovalActionId,
        Integer actionSeq,
        String actionType,
        String actionBy,
        OffsetDateTime actionAt,
        String reason,
        String evidenceRef
) {
}
