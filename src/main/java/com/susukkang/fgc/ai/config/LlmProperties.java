package com.susukkang.fgc.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 설명 : fgc.llm.* 설정(SER-013). 모델 ID·입력 크기·출력 상한·시간 초과를 코드에 박지 않는다.
 * apiKey 가 비어 있으면 게이트웨이는 외부 호출 없이 AI 기능만 끈다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-23
 */
@ConfigurationProperties("fgc.llm")
public record LlmProperties(
        String apiKey,
        String baseUrl,
        String model,
        Duration timeout,
        int maxInputChars,
        long maxOutputTokens,
        Duration cooldown) {
}
