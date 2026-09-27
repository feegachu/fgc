package com.susukkang.fgc.common.exception;

import com.susukkang.fgc.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

import java.util.Objects;

/**
 * 설명 : 화면 컨트롤러의 업무 예외를 API와 같은 HTTP 상태·코드·문구의 오류 화면으로 변환한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@ControllerAdvice(annotations = Controller.class)
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class MpaExceptionHandler {

    private final GlobalExceptionHandler globalExceptionHandler;

    @ExceptionHandler(FgcBusinessException.class)
    public ModelAndView handleBusinessException(FgcBusinessException exception) {
        // 1. 공통 예외 처리기의 HTTP 상태·메시지와 운영 환경의 상세 정보 숨김 정책을 재사용한다.
        ResponseEntity<ApiResponse<Void>> response = globalExceptionHandler.handleBusinessException(exception);
        ApiResponse<Void> body = Objects.requireNonNull(response.getBody());

        // 2. API의 JSON 응답 대신 화면을 렌더링하되, 업무 오류에 지정된 HTTP 상태를 유지한다.
        ModelAndView view = new ModelAndView("error/business");
        view.setStatus(response.getStatusCode());
        view.addObject("status", response.getStatusCode().value());
        view.addObject("errorTitle", response.getStatusCode().equals(HttpStatus.BAD_REQUEST)
                ? "입력 오류" : "처리 오류");
        view.addObject("errorCode", body.error().code());
        view.addObject("errorMessage", body.error().message());
        view.addObject("errorDetail", body.error().detail());
        view.addObject("requestId", body.requestId());
        return view;
    }
}
