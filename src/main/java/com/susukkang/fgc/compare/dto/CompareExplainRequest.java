package com.susukkang.fgc.compare.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 설명 : 요약 요청. 서버가 보관한 계산 결과의 ID만 받고 화면이 보낸 금액·순위는 쓰지 않는다.
 */
public record CompareExplainRequest(@NotBlank String rankResultId) {
}
