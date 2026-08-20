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
        assertThat(attributesOf(errorRequest(500))).containsEntry("errorCode", "FGC-COMMON-500");
    }

    /*
     * 3-2 오류코드 표준 매핑표(05_인터페이스정의서 :242~261)에 없는 상태에는 코드를 붙이지 않는다.
     *
     * 404 가 특히 중요하다 — FgcErrorCode.COMMON_004 는 enum 에만 있고 3-1 절(:205)의 404 는
     * "그 ID 의 자료가 없음" 이다. 경로 자체가 없는 MPA 404 에 그 코드를 재사용하면 한 코드가
     * 문구 둘을 갖게 되어 SIR-007 규칙 3 을 오히려 어긴다 (2026-08-20 PR #313 리뷰 판정).
     * 아무 코드나 붙이지 않고 비워 두어야 화면이 요청 ID 만 보여준다.
     */
    @Test
    void omits_error_code_for_statuses_absent_from_the_standard_mapping_table() {
        assertThat(attributesOf(errorRequest(404))).doesNotContainKey("errorCode");
        assertThat(attributesOf(errorRequest(502))).doesNotContainKey("errorCode");
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
