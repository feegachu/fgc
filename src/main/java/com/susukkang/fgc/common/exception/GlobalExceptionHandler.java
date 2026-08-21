package com.susukkang.fgc.common.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.susukkang.fgc.common.web.ApiError;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.RequestIdContext;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice(annotations = RestController.class)
@RequiredArgsConstructor

/**
 * 애플리케이션에서 발생한 예외를 공통 ApiResponse 오류 형식으로 변환한다.
 *
 * 예외 유형에 따라 HTTP 상태와 FgcErrorCode를 결정하고,
 * 사용자 메시지를 생성하여 ApiError로 반환한다.
 *
 * annotations = RestController.class 인 이유 — 인터페이스정의서 3-3 규칙 3
 *  이 제한이 없으면 이 advice 가 MPA @Controller 의 예외까지 잡아 JSON 으로 내려보낸다.
 *  그러면 templates/error/403·404·500.html 이 영원히 렌더링되지 않는다(브라우저로 없는 경로를
 *  열면 오류 화면 대신 JSON 이 그대로 보였다). @RestController 만 걸러내면 MPA 예외는
 *  Spring Boot 의 /error 디스패치로 흘러가 오류 화면이 뜬다.
 *  @Controller 로 거르면 안 된다 — @RestController 가 @Controller 의 메타 애노테이션이라 둘 다 잡힌다.
 *
 *  규칙 1("전부 @RestControllerAdvice 한 클래스에서")과의 관계
 *   규칙 1 과 규칙 3 은 한 advice 로 동시에 만족할 수 없다. 범위를 안 걸면 MPA 도 JSON 을 받아
 *   규칙 3 이 깨지고, 걸면 "한 클래스"가 아니게 된다. 규칙 3 이 사용자가 보는 결과이므로 그쪽을 택했다.
 *
 *  아직 안 메운 구멍 — MPA 저장 화면이 생길 때
 *   지금 상태를 바꾸는 엔드포인트는 전부 @RestController 위에 있다(ContractController,
 *   ValidationRunController). @Controller 는 로그인 화면·대시보드·정적 화면뿐이고 전부 GET 이라
 *   업무 예외를 던지지 않는다. 그래서 이 제한이 현재 잃는 것은 없다.
 *   CONT-W03·TRAN-W02 같은 MPA 저장 화면이 @Controller 로 붙는 순간, 그 화면의
 *   FgcBusinessException(예: FGC-CONT-001 계약번호 중복)이 3-2 매핑표를 못 타고
 *   error/500.html 의 일반 문구로만 나간다. 그때는 이 클래스의 매핑을 재사용해 오류 화면을
 *   렌더링하는 @ControllerAdvice 를 따로 두어야 한다 — 매핑을 복사하지 말 것.
 */
public class GlobalExceptionHandler {

    private final FgcMessageResolver messageResolver;
    private final ConstraintErrorCodeResolver constraintResolver;
    private final Environment environment;

    @ExceptionHandler(FgcBusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            FgcBusinessException exception
    ) {
        FgcErrorCode errorCode = exception.getErrorCode();

        log.warn(
                "[{}] Business exception: {}",
                RequestIdContext.current(),
                errorCode.getCode()
        );

        ApiError apiError = createApiError(
                errorCode,
                exception.getField(),
                withRequestId(errorCode, exception.getParams()),
                productionDetail(exception.getDetail())
        );

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.failure(apiError));
    }

    /** {requestId} 를 쓰는 유일한 문구 — 이 키는 COMMON_500 과 AUDT_001 이 공유한다 (코드리뷰 2차 반영). */
    private static final String INTERNAL_MESSAGE_KEY = "error.common.internal";

    /**
     * {requestId} 자리표시자를 쓰는 문구가 호출부의 파라미터 누락으로
     * "요청번호 {requestId}를…" 원문 그대로 노출되지 않게 기본값을 주입한다 (QA-07 · #259).
     *
     * 대상을 문구 키 기준으로 좁히는 이유(코드리뷰 반영, 2차) — error.params 는 문서상
     * "문구의 치환값"(인터페이스정의서 :157)이라, 문구가 쓰지 않는 키를 모든 오류에 얹으면
     * 응답 계약이 문서 예시(:143~148)와 달라진다. enum 동일성(COMMON_500)으로 좁히면
     * 같은 문구 키를 공유하는 AUDT_001 이 빠진다(FgcErrorCode :197·:229) — 그래서
     * messageKey 기준으로 판정한다. 이 전제는 GlobalExceptionHandlerTest 의
     * requestIdPlaceholderIsOnlyUsedByInternalMessage(properties 스캔)와
     * fillsRequestIdForEveryErrorCodeUsingTheInternalMessage(enum 전수)가 지킨다.
     * 호출부가 이미 requestId 를 넣었으면 그대로 둔다.
     */
    private Map<String, Object> withRequestId(FgcErrorCode errorCode, Map<String, Object> params) {
        if (!INTERNAL_MESSAGE_KEY.equals(errorCode.getMessageKey())) {
            return params;
        }
        if (params != null && params.containsKey("requestId")) {
            return params;
        }
        Map<String, Object> merged = new LinkedHashMap<>(params == null ? Map.of() : params);
        merged.put("requestId", RequestIdContext.current());
        return merged;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException exception
    ) {
        FieldError fieldError = exception
                .getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .orElse(null);

        String field = fieldError == null
                ? null
                : fieldError.getField();

        return validationError(field);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(
            BindException exception
    ) {
        FieldError fieldError = exception
                .getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .orElse(null);

        String field = fieldError == null
                ? null
                : fieldError.getField();

        return validationError(field);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException exception
    ) {
        String field = exception
                .getConstraintViolations()
                .stream()
                .findFirst()
                .map(violation ->
                        violation.getPropertyPath().toString()
                )
                .orElse(null);

        return validationError(field);
    }


    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception
    ) {
        return validationError(exception.getName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(
            MissingServletRequestParameterException exception
    ) {
        return validationError(exception.getParameterName());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
            DataIntegrityViolationException exception
    ) {
        FgcErrorCode errorCode = constraintResolver
                .resolve(exception)
                .orElse(FgcErrorCode.COMMON_500);

        log.error(
                "[{}] Database integrity error: {}",
                RequestIdContext.current(),
                errorCode.getCode(),
                exception
        );

        Map<String, Object> params =
                errorCode == FgcErrorCode.COMMON_500
                        ? Map.of(
                        "requestId",
                        RequestIdContext.current()
                )
                        : Map.of();

        ApiError apiError = createApiError(
                errorCode,
                null,
                params,
                productionDetail(exception.getMostSpecificCause().getMessage())
        );

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.failure(apiError));
    }

    // 2026-08-07 yslee - 접근 거부 예외에 대한 공통 오류 응답 처리 적용
    // 기존 코드: 접근 거부 예외가 일반 예외 처리기로 전달되었다.
    // 문제: 권한이 없는 요청이 HTTP 403 대신 HTTP 500으로 응답할 수 있었다.
    // 개선: AccessDeniedException을 별도로 처리하여 HTTP 403과 FGC-AUTH-003을 반환한다.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            AccessDeniedException exception
    ) {
        FgcErrorCode errorCode = FgcErrorCode.AUTH_003;

        log.warn(
                "[{}] Access denied",
                RequestIdContext.current()
        );

        ApiError apiError = createApiError(
                errorCode,
                null,
                Map.of(),
                productionDetail(exception.getMessage())
        );

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.failure(apiError));
    }

    /*
     * NoResourceFoundException 핸들러는 지웠다 (2026-08-11).
     *
     * 원래 목적은 "아직 만들지 않은 화면 경로를 클릭하면 500 이 나던 것"을 404 로 바꾸는 것이었는데,
     * 이제 templates/error/404.html 이 그 자리를 대신한다. 그리고 이 advice 가
     * annotations = RestController.class 로 제한되면서 애초에 호출되지도 않는다 —
     * NoResourceFoundException 은 컨트롤러가 아니라 정적 리소스 핸들러가 던져서
     * 적용 대상 타입 자체가 없기 때문이다.
     *
     * 부작용: 로그인 상태에서 /api/** 의 오타 경로는 FGC 봉투가 아니라 Spring Boot 기본 오류 JSON 이
     * 나간다(미인증이면 그 전에 401). 봉투가 필요해지면 여기가 아니라 FgcErrorAttributes 를 넓혀야 한다.
     */

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(
            Exception exception
    ) {
        String requestId = RequestIdContext.current();

        log.error(
                "[{}] Unexpected server error",
                requestId,
                exception
        );

        FgcErrorCode errorCode = FgcErrorCode.COMMON_500;
        Map<String, Object> params = Map.of(
                "requestId",
                requestId
        );

        ApiError apiError = createApiError(
                errorCode,
                null,
                params,
                productionDetail(exception.getMessage())
        );

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.failure(apiError));
    }

    private ResponseEntity<ApiResponse<Void>> validationError(
            String field
    ) {
        FgcErrorCode errorCode = FgcErrorCode.COMMON_002;

        Map<String, Object> params = Map.of(
                "field",
                field == null ? "" : field
        );

        ApiError apiError = createApiError(
                errorCode,
                field,
                params,
                null
        );

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.failure(apiError));
    }

    private ApiError createApiError(
            FgcErrorCode errorCode,
            String field,
            Map<String, Object> params,
            String detail
    ) {
        String message = messageResolver.resolve(
                errorCode,
                params
        );

        return new ApiError(
                errorCode.getCode(),
                message,
                field,
                params,
                detail
        );
    }

    private String productionDetail(String detail) {
        boolean production = environment.acceptsProfiles(
                Profiles.of("prod", "production")
        );

        return production ? null : detail;
    }
}
