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
    void resolvesKoreanMessageFromErrorCodeAndMessageKey() {
        String message = resolver.resolve(FgcErrorCode.CONT_001, Map.of());

        assertThat(message)
                .isEqualTo("저장 불가 — 이미 등록된 계약번호입니다.");
    }

    @Test
    void replacesMessagePlaceholderWithParameter() {
        String message = resolver.resolve(
                FgcErrorCode.VRUN_002,
                Map.of("n", 2)
        );

        assertThat(message).isEqualTo("확정 불가 — 2개 조건이 남았습니다. 아래 체크리스트를 확인하세요.");
    }

    /** SRC-074: 호출부가 field 를 넘겨도 화면 문구에 원문 필드 키를 보이지 않는다. */
    @Test
    void doesNotExposeFieldKeyEvenWhenCallerPassesIt() {
        assertThat(resolver.resolve(FgcErrorCode.COMMON_002, Map.of("field", "contractNo")))
                .isEqualTo("입력값을 확인하세요.");
        assertThat(resolver.resolve(FgcErrorCode.IMPL_POLICY_MISSING, Map.of("field", "policyVersionId")))
                .isEqualTo("적용 정책 또는 계산 근거가 부족해 계산할 수 없습니다.");
    }

    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource messageSource =
                new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        return messageSource;
    }
}
