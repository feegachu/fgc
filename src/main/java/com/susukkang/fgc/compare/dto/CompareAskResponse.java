package com.susukkang.fgc.compare.dto;

/**
 * 설명 : 질문 답변. source 는 LLM(근거 안에서 풀어 쓴 답) | RULE(용어 설명·안내 문장) | OUT_OF_SCOPE(범위 밖 안내).
 */
public record CompareAskResponse(String answer, String source) {

    public static final String LLM = "LLM";
    public static final String RULE = "RULE";
    public static final String OUT_OF_SCOPE = "OUT_OF_SCOPE";
}
