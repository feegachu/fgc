package com.susukkang.fgc.policy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * FUN-016 상품위원회 메타데이터. 정책의 수수료 규칙이 가리키는 판매버전에 저장된 값을 그대로 보여 준다.
 * FGC는 상품위원회의 법정 심의·의결을 대체하지 않으며 이 값은 참조정보다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "정책이 적용되는 판매버전의 상품위원회·기초서류·판매 상태(참조정보)")
public record PolicyOfferingGovernanceResponse(
        Long productOfferingId,
        String productName,
        String offeringVersion,
        @Schema(description = "기초서류 버전") String basicDocumentVersion,
        @Schema(description = "판매개시일") LocalDate salesStartDate,
        @Schema(description = "판매종료일", nullable = true) LocalDate salesEndDate,
        @Schema(description = "상품위원회 승인일", nullable = true) LocalDate committeeApprovalDate,
        @Schema(description = "상품위원회 승인문서 참조", nullable = true) String committeeDocumentRef,
        @Schema(description = "판매 상태 APPROVED·REVISED·SUSPENDED·STOPPED. 미등록이면 null", nullable = true)
        String governanceStatus
) { }
