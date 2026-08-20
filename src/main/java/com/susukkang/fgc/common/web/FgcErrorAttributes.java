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
 *
 * 404 를 매핑하지 않는 이유 (2026-08-20, PR #313 리뷰 판정)
 *  3-2 오류코드 표준 매핑표(05_인터페이스정의서 :242~261)에 404 계열 공통 코드가 **없다**.
 *  FgcErrorCode.COMMON_004 는 enum 에만 있고, 3-1 절(:205)이 규정한 404 는 "그 ID 의 자료가 없음"
 *  이며 실제 호출부 30여 곳도 전부 Map.of("id", ...) 로 던진다. 경로(화면) 자체가 없는 MPA 404 에
 *  같은 코드를 붙이면 한 코드가 "요청한 데이터를 찾을 수 없습니다.({id})" 와 "요청한 화면이 없습니다."
 *  두 문구를 갖게 되어 규칙 3 을 오히려 어긴다. 그래서 404 화면은 코드 없이 요청 ID 만 보여준다.
 *  "경로 없음" 전용 코드가 필요하면 3-2 표 등재부터다 — 여기서 임의로 만들지 않는다.
 */
@Component
public class FgcErrorAttributes extends DefaultErrorAttributes {

    /** HTTP 상태 → 3-2 매핑표에 **등재된** 오류코드만. 표에 없는 상태는 코드를 붙이지 않는다. */
    private static final Map<Integer, FgcErrorCode> ERROR_CODE_BY_STATUS = Map.of(
            403, FgcErrorCode.AUTH_003,
            500, FgcErrorCode.COMMON_500
    );

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(request, options);
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME, RequestAttributes.SCOPE_REQUEST);
        if (requestId != null) {
            attributes.put("requestId", requestId);
        }
        FgcErrorCode errorCode = ERROR_CODE_BY_STATUS.get(attributes.get("status"));
        if (errorCode != null) {
            attributes.put("errorCode", errorCode.getCode());
        }
        return attributes;
    }
}
