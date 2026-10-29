package com.susukkang.fgc.compare.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 설명 : 자연어 질문. rankResultId 는 서버가 보관한 순위 결과의 ID 이고 화면이 보낸 금액·순위는 쓰지 않는다(없으면 개념 질문).
 */
public record CompareAskRequest(String rankResultId, @NotBlank @Size(max = 200) String question) {
}
