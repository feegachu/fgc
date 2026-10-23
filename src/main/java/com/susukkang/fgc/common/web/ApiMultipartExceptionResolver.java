package com.susukkang.fgc.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.handler.AbstractHandlerExceptionResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** #637: multipart 파싱 실패는 컨트롤러 선택 전이라 annotation 범위 Advice가 적용되지 않는다. */
@Component
public class ApiMultipartExceptionResolver extends AbstractHandlerExceptionResolver {
    private final GlobalExceptionHandler errors;
    private final ObjectMapper mapper;

    public ApiMultipartExceptionResolver(GlobalExceptionHandler errors, ObjectMapper mapper) {
        this.errors = errors;
        this.mapper = mapper;
        setOrder(Ordered.HIGHEST_PRECEDENCE);
    }

    @Override
    protected ModelAndView doResolveException(HttpServletRequest request, HttpServletResponse response,
                                             Object handler, Exception exception) {
        if (!request.getServletPath().startsWith("/api/")
                || !(exception instanceof MaxUploadSizeExceededException sizeError)) return null;
        // 메시지·오류코드·requestId 생성은 기존 공통 핸들러 한 곳을 재사용한다.
        var entity = errors.handleUploadSizeExceeded(sizeError);
        response.setStatus(entity.getStatusCode().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        try {
            mapper.writeValue(response.getOutputStream(), entity.getBody());
        } catch (IOException ex) {
            // 클라이언트 전송 실패 뒤 다른 resolver가 오류 봉투를 중복 기록하지 않게 소비한다.
            logger.warn("multipart 크기 오류 응답 전송 실패", ex);
        }
        return new ModelAndView();
    }
}
