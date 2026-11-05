package com.susukkang.fgc.assistant.controller;

import com.susukkang.fgc.assistant.dto.*;
import com.susukkang.fgc.assistant.service.PicoAskService;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.exception.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ai/pico/ask")
@PreAuthorize(Roles.ANY_ROLE)
public class PicoAskController {
    private final PicoAskService service;
    private final GlobalExceptionHandler errors;
    @PostMapping
    @Operation(operationId="picoAsk",summary="승인된 업무 가이드의 대화형 질문 답변",description="질문·최근 대화와 역할별 승인 근거로 답변합니다. START_GUIDE에만 서버가 검증한 시작 위치를 반환하며 지급 시작은 prepare/start에서 다시 검사합니다. 금융 쓰기 없음.")
    public ApiResponse<PicoAskResponse> ask(@Valid @RequestBody PicoAskRequest request) { return ApiResponse.success(service.ask(request)); }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<Void>> malformed() {
        return errors.handleBusinessException(new FgcBusinessException(FgcErrorCode.COMMON_002,"request",java.util.Map.of("field","request"),null));
    }
}
