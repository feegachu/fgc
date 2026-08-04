package com.susukkang.fgc.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FgcMessageResolverTest {

    private final FgcMessageResolver resolver = new FgcMessageResolver(
            messageSource()
    );

    @Test
    void 오류_코드의_메시지_키로_한국어_메시지를_조회한다() {
        String message = resolver.resolve(FgcErrorCode.CONT_001, Map.of());

        assertThat(message)
                .isEqualTo("저장 불가 — 이미 등록된 계약번호입니다.");
    }

    @Test
    void 메시지의_플레이스홀더를_파라미터로_치환한다() {
        String message = resolver.resolve(
                FgcErrorCode.COMMON_002,
                Map.of("field", "contractNo")
        );

        assertThat(message).isEqualTo("입력값을 확인하세요. (contractNo)");
    }

    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource messageSource =
                new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        return messageSource;
    }
}
