package com.susukkang.fgc.common.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

import java.util.Map;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor

/**
 * 애플리케이션에서 발생한 예외를 공통 ApiResponse 오류 형식으로 변환한다.
 *
 * 예외 유형에 따라 HTTP 상태와 FgcErrorCode를 결정하고,
 * 사용자 메시지를 생성하여 ApiError로 반환한다.
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
                exception.getParams(),
                productionDetail(exception.getDetail())
        );

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.failure(apiError));
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

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            AccessDeniedException exception
    ) {
        FgcErrorCode errorCode = FgcErrorCode.AUTH_003;
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
