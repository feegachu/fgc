package com.susukkang.fgc.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearRequestId() {
        RequestIdContext.clear();
    }

    @Test
    void reusesValidRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(RequestIdFilter.HEADER_NAME, "client-request-123");
        AtomicReference<String> requestIdInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                requestIdInChain.set(RequestIdContext.current())
        );

        assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME))
                .isEqualTo("client-request-123");
        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME))
                .isEqualTo("client-request-123");
        assertThat(requestIdInChain.get()).isEqualTo("client-request-123");
        assertThat(MDC.get(RequestIdContext.MDC_KEY)).isNull();
    }

    @Test
    void generatesNewRequestIdForInvalidRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(RequestIdFilter.HEADER_NAME, "잘못된 요청 ID");

        filter.doFilter(request, response, (req, res) -> { });

        String generatedId = response.getHeader(RequestIdFilter.HEADER_NAME);
        assertThat(generatedId).matches("\\d{8}-[a-f0-9]{6}");
        assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME))
                .isEqualTo(generatedId);
    }
}
