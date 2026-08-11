package com.susukkang.fgc.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** 오류 화면의 "요청번호 {requestId} 를 담당자에게 알려주세요"(부록 A)가 실제 값으로 채워지는지. */
class FgcErrorAttributesTest {

    private final FgcErrorAttributes errorAttributes = new FgcErrorAttributes();

    @Test
    void puts_request_id_from_filter_attribute_into_error_model() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/boom");
        request.setAttribute(RequestIdFilter.ATTRIBUTE_NAME, "req-123");

        assertThat(attributesOf(request)).containsEntry("requestId", "req-123");
    }

    /** 필터를 안 탄 요청(예: 컨테이너가 바로 낸 오류)까지 requestId=null 로 채우지는 않는다. */
    @Test
    void omits_request_id_when_filter_did_not_run() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/boom");

        assertThat(attributesOf(request)).doesNotContainKey("requestId");
    }

    private java.util.Map<String, Object> attributesOf(MockHttpServletRequest request) {
        return errorAttributes.getErrorAttributes(
                new ServletWebRequest(request), ErrorAttributeOptions.defaults());
    }
}
