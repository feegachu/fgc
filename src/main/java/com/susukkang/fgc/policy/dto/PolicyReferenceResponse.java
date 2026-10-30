package com.susukkang.fgc.policy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * FUN-016 정책 근거 info 패널 한 줄. 정책에 연결된 근거 참조와 레지스트리 항목명, 이 정책버전에 저장된 상세 설명을 합친다.
 * 저장된 상세가 없으면 detail* 필드가 null이며 화면은 공통 샘플 설명으로 채우지 않는다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "정책 근거 참조와 저장된 상세 설명")
public record PolicyReferenceResponse(
        @Schema(description = "정책에 저장된 참조 원문(코드 뒤 설명이 붙은 과거 형식 포함)") String ref,
        @Schema(description = "참조에서 읽은 REG/SRC 코드. 형식이 아니면 null", nullable = true) String code,
        @Schema(description = "REGULATION(regulationRefs) 또는 SOURCE(sourceRefs)") String group,
        @Schema(description = "근거 코드 레지스트리 항목명. 미등록·SRC는 null일 수 있음", nullable = true) String registryTitle,
        @Schema(description = "이 정책버전에 저장된 근거 제목", nullable = true) String detailTitle,
        @Schema(description = "이 정책버전에 저장된 근거 설명", nullable = true) String explanation,
        @Schema(description = "검증된 http(s) 링크", nullable = true) String url
) { }
