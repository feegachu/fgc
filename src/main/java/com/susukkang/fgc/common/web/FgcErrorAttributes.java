package com.susukkang.fgc.common.web;

import com.susukkang.fgc.common.exception.FgcErrorCode;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

/**
 * 오류 화면(error/403·404·500.html)이 요청번호와 오류코드를 보여줄 수 있도록 /error 모델을 넓힌다.
 *
 * 부록 A FGC-COMMON-500 이 "{requestId} 를 실제 값으로 치환"하도록 규정한다. 그런데
 * {@link RequestIdFilter} 는 요청 속성 fgc.requestId 와 MDC 에만 값을 넣고, Spring Boot 기본
 * /error 모델에는 timestamp·status·error·message·path 만 들어간다. 그래서 손대지 않으면
 * 500.html 의 ${requestId} 가 항상 '-' 로 떨어져 "요청번호를 담당자에게 알려주세요"가 무의미해진다.
 *
 * 필터가 이미 만들어 둔 값을 옮겨 담기만 한다 — 여기서 새로 만들지 않는다. 그래야 응답 헤더
 * X-Request-Id, 로그 MDC, 오류 화면이 같은 번호를 가리킨다.
 *
 * errorCode 는 인터페이스정의서 3-3 SIR-007 규칙 3 때문에 붙였다 — 같은 상황이면 MPA 오류 화면과
 * Ajax JSON 봉투가 "코드와 문구" 를 똑같이 보여야 한다. Ajax 쪽은 GlobalExceptionHandler 가
 * FgcErrorCode 를 그대로 내보내므로, 화면도 같은 enum 에서 코드를 가져와야 둘이 갈라지지 않는다.
 * 템플릿에 'FGC-AUTH-003' 문자열을 직접 적으면 enum 이 바뀌는 순간 화면만 옛 코드를 보여준다.
 */
@Component
public class FgcErrorAttributes extends DefaultErrorAttributes {

    /** HTTP 상태 → 3-2 매핑표의 오류코드. 표에 없는 상태는 전부 FGC-COMMON-500 이다. */
    private static final Map<Integer, FgcErrorCode> ERROR_CODE_BY_STATUS = Map.of(
            403, FgcErrorCode.AUTH_003,
            404, FgcErrorCode.COMMON_004
    );

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(request, options);
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME, RequestAttributes.SCOPE_REQUEST);
        if (requestId != null) {
            attributes.put("requestId", requestId);
        }
        if (attributes.get("status") instanceof Integer status && status >= 400) {
            attributes.put(
                    "errorCode",
                    ERROR_CODE_BY_STATUS.getOrDefault(status, FgcErrorCode.COMMON_500).getCode()
            );
        }
        return attributes;
    }
}
