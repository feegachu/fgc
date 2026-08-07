package com.susukkang.fgc.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 요청별 추적 ID를 생성하거나 전달받아 요청, 응답 및 로그에 적용한다.
 *
 * 유효한 X-Request-Id가 요청 헤더에 있으면 재사용하고,
 * 없거나 형식이 잘못된 경우 서버에서 새로 생성한다.
 */

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String ATTRIBUTE_NAME = "fgc.requestId";

    private static final Pattern VALID_REQUEST_ID =
            Pattern.compile("^[A-Za-z0-9._-]{1,80}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String requestId = resolveRequestId(request);

        request.setAttribute(ATTRIBUTE_NAME, requestId);
        response.setHeader(HEADER_NAME, requestId);
        RequestIdContext.set(requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestIdContext.clear();
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(HEADER_NAME);

        if (requestId == null
                || requestId.isBlank()
                || !VALID_REQUEST_ID.matcher(requestId).matches()) {
            return RequestIdContext.generate();
        }

        return requestId;
    }
}