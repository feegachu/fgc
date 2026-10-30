package com.susukkang.fgc.compare.dto;

/**
 * 설명 : 순위 결과를 풀어 쓴 요약. 요약이 실패해도 순위 응답은 그대로 쓰며, 이 응답이 summaryError 로 실패를 알린다.
 */
public record CompareExplainResponse(
        String rankResultId,
        String summary,
        /** RULE(규칙형 문장) | LLM */
        String summarySource,
        String summaryError) {
}
