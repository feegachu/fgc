package com.susukkang.fgc.common.exception;

import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.RequestIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 공통 예외 핸들러의 메시지 치환 파라미터 회귀 테스트 (QA-07 · #259)
 *
 * COMMON_500 문구는 {requestId} 자리표시자를 갖는다. 호출부가 파라미터를 빠뜨리면
 * 화면에 "요청번호 {requestId}를…" 원문이 그대로 노출됐다 — 핸들러가 기본값을
 * 주입해 이 사고를 구조적으로 막는지 검증한다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-08-21
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private ConstraintErrorCodeResolver constraintResolver;
    @Mock
    private Environment environment;

    private GlobalExceptionHandler handler() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        return new GlobalExceptionHandler(
                new FgcMessageResolver(messageSource),
                constraintResolver,
                environment
        );
    }

    @AfterEach
    void tearDown() {
        RequestIdContext.clear();
    }

    @Test
    void fillsRequestIdWhenCallerOmitsIt() {
        RequestIdContext.set("20260821-test01");

        ResponseEntity<ApiResponse<Void>> response = handler()
                .handleBusinessException(new FgcBusinessException(FgcErrorCode.COMMON_500));

        String message = response.getBody().error().message();
        assertThat(message).doesNotContain("{requestId}");
        assertThat(message).contains("20260821-test01");
    }

    @Test
    void keepsCallerProvidedRequestId() {
        RequestIdContext.set("20260821-test01");

        ResponseEntity<ApiResponse<Void>> response = handler()
                .handleBusinessException(new FgcBusinessException(
                        FgcErrorCode.COMMON_500,
                        Map.of("requestId", "CALLER-SET")
                ));

        assertThat(response.getBody().error().message()).contains("CALLER-SET");
    }

    @Test
    void notFoundMessageResolvesIdPlaceholderWhenProvided() {
        ResponseEntity<ApiResponse<Void>> response = handler()
                .handleBusinessException(new FgcBusinessException(
                        FgcErrorCode.COMMON_004,
                        Map.of("id", 42L)
                ));

        String message = response.getBody().error().message();
        assertThat(message).doesNotContain("{id}");
        assertThat(message).contains("42");
    }
}
