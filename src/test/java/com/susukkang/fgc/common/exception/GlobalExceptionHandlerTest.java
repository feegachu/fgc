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

    // 주입 범위를 문구 키(error.common.internal)로 좁힌 전제(리뷰 반영)를 지키는 가드 —
    // messages.properties 에서 {requestId} 를 쓰는 문구가 늘어나면 여기가 깨져
    // withRequestId 의 판정 기준을 함께 갱신하게 된다.
    @Test
    void requestIdPlaceholderIsOnlyUsedByInternalMessage() throws Exception {
        java.util.Properties messages = new java.util.Properties();
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(
                getClass().getResourceAsStream("/messages.properties"),
                java.nio.charset.StandardCharsets.UTF_8)) {
            messages.load(reader);
        }
        java.util.List<String> usingRequestId = messages.stringPropertyNames().stream()
                .filter(key -> messages.getProperty(key).contains("{requestId}"))
                .sorted()
                .toList();
        assertThat(usingRequestId).containsExactly("error.common.internal");
    }

    // 같은 문구 키를 공유하는 모든 코드(COMMON_500·AUDT_001)가 보호되는지 enum 전수로 검증 —
    // enum 동일성 판정이 문구 키 공유를 놓친다는 2차 리뷰 지적의 재발 방지.
    @Test
    void fillsRequestIdForEveryErrorCodeUsingTheInternalMessage() {
        RequestIdContext.set("20260821-test01");
        java.util.List<FgcErrorCode> internalMessageCodes = java.util.Arrays.stream(FgcErrorCode.values())
                .filter(code -> "error.common.internal".equals(code.getMessageKey()))
                .toList();
        assertThat(internalMessageCodes).contains(FgcErrorCode.COMMON_500, FgcErrorCode.AUDT_001);

        for (FgcErrorCode code : internalMessageCodes) {
            ResponseEntity<ApiResponse<Void>> response = handler()
                    .handleBusinessException(new FgcBusinessException(code));
            String message = response.getBody().error().message();
            assertThat(message).doesNotContain("{requestId}");
            assertThat(message).contains("20260821-test01");
        }
    }

    // DataIntegrityViolation 폴백 경로도 같은 판정을 타는지 검증 (3차 리뷰 반영 —
    // handleBusinessException 만 커버하던 기존 테스트의 사각지대).
    @Test
    void fillsRequestIdOnDataIntegrityFallbackPath() {
        RequestIdContext.set("20260821-test01");
        org.mockito.BDDMockito.given(constraintResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .willReturn(java.util.Optional.empty());

        ResponseEntity<ApiResponse<Void>> response = handler().handleDataIntegrityViolation(
                new org.springframework.dao.DataIntegrityViolationException("boom",
                        new RuntimeException("root")));

        String message = response.getBody().error().message();
        assertThat(message).doesNotContain("{requestId}");
        assertThat(message).contains("20260821-test01");
    }

    @Test
    void doesNotInjectRequestIdIntoOtherErrorCodesParams() {
        ResponseEntity<ApiResponse<Void>> response = handler()
                .handleBusinessException(new FgcBusinessException(
                        FgcErrorCode.COMMON_004,
                        Map.of("id", 7L)
                ));

        assertThat(response.getBody().error().params()).doesNotContainKey("requestId");
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
