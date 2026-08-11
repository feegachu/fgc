package com.susukkang.fgc.common.web;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

/**
 * 오류 화면(error/403·404·500.html)이 요청번호를 보여줄 수 있도록 /error 모델에 requestId 를 얹는다.
 *
 * 부록 A FGC-COMMON-500 이 "{requestId} 를 실제 값으로 치환"하도록 규정한다. 그런데
 * {@link RequestIdFilter} 는 요청 속성 fgc.requestId 와 MDC 에만 값을 넣고, Spring Boot 기본
 * /error 모델에는 timestamp·status·error·message·path 만 들어간다. 그래서 손대지 않으면
 * 500.html 의 ${requestId} 가 항상 '-' 로 떨어져 "요청번호를 담당자에게 알려주세요"가 무의미해진다.
 *
 * 필터가 이미 만들어 둔 값을 옮겨 담기만 한다 — 여기서 새로 만들지 않는다. 그래야 응답 헤더
 * X-Request-Id, 로그 MDC, 오류 화면이 같은 번호를 가리킨다.
 */
@Component
public class FgcErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(request, options);
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME, RequestAttributes.SCOPE_REQUEST);
        if (requestId != null) {
            attributes.put("requestId", requestId);
        }
        return attributes;
    }
}
