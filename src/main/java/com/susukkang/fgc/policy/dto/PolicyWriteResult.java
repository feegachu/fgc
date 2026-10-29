package com.susukkang.fgc.policy.dto;

import com.susukkang.fgc.common.code.PolicyStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/** IF-API-79·80·81·75 저장 결과. allowedActions는 현재 역할과 정책 상태로 결정한다. */
@Schema(description = "서버가 결정한 정책 판 번호·상태·승인 정보와 허용 동작")
public record PolicyWriteResult(
        Long policyVersionId,
        String policyCode,
        Integer versionNo,
        PolicyStatus status,
        OffsetDateTime updatedAt,
        String approvedBy,
        OffsetDateTime approvedAt,
        List<String> allowedActions
) {
}
