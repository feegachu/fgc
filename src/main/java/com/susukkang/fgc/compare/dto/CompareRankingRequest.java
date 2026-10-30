package com.susukkang.fgc.compare.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 설명 : 상품 비교 순위 요청. 입력은 카테고리 코드와 선택 세부 조건뿐이며 설계사 ID는 세션에서만 취득한다.
 */
public record CompareRankingRequest(@NotBlank String categoryCode, @Valid CompareRankingFilters filters) {
}
