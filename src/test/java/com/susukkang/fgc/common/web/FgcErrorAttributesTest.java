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

    /*
     * SIR-007 규칙 3 — 화면과 Ajax 가 같은 코드를 보여야 한다.
     * 코드 문자열을 템플릿에 직접 적지 않으려고 여기서 FgcErrorCode 를 꺼내 모델에 담는다.
     */
    @Test
    void maps_http_status_to_the_error_code_shown_on_the_error_page() {
        assertThat(attributesOf(errorRequest(403))).containsEntry("errorCode", "FGC-AUTH-003");
        assertThat(attributesOf(errorRequest(404))).containsEntry("errorCode", "FGC-COMMON-004");
        assertThat(attributesOf(errorRequest(500))).containsEntry("errorCode", "FGC-COMMON-500");
        // 3-2 매핑표에 없는 상태(예: 502)는 전부 공통 500 코드로 떨어진다.
        assertThat(attributesOf(errorRequest(502))).containsEntry("errorCode", "FGC-COMMON-500");
    }

    /** 오류가 아닌 요청까지 오류코드로 물들이지 않는다. */
    @Test
    void omits_error_code_when_status_is_not_an_error() {
        assertThat(attributesOf(errorRequest(200))).doesNotContainKey("errorCode");
    }

    private MockHttpServletRequest errorRequest(int status) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/boom");
        request.setAttribute("jakarta.servlet.error.status_code", status);
        return request;
    }

    private java.util.Map<String, Object> attributesOf(MockHttpServletRequest request) {
        return errorAttributes.getErrorAttributes(
                new ServletWebRequest(request), ErrorAttributeOptions.defaults());
    }
}
