package com.susukkang.fgc.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @AfterEach
    void clearRequestId() {
        RequestIdContext.clear();
    }

    @Test
    void successContainsDataAndCurrentRequestId() {
        RequestIdContext.set("test-request-123");

        ApiResponse<String> response = ApiResponse.success("result");

        assertThat(response.data()).isEqualTo("result");
        assertThat(response.error()).isNull();
        assertThat(response.requestId()).isEqualTo("test-request-123");
    }

    @Test
    void failureContainsErrorAndCurrentRequestId() {
        RequestIdContext.set("test-request-456");
        ApiError error = new ApiError(
                "FGC-TEST-001",
                "테스트 오류입니다.",
                "field",
                Map.of("value", 1),
                null
        );

        ApiResponse<Void> response = ApiResponse.failure(error);

        assertThat(response.data()).isNull();
        assertThat(response.error()).isEqualTo(error);
        assertThat(response.requestId()).isEqualTo("test-request-456");
    }
}
