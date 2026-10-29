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
    @Operation(summary="승인된 지급 가이드의 읽기 전용 질문 답변",description="설명형 첫 연결: validatedGuide=null. 실제 상태/금액/실행 없음. 제한 업무 어휘와 승인 근거만 외부 전송.")
    public ApiResponse<PicoAskResponse> ask(@Valid @RequestBody PicoAskRequest request) { return ApiResponse.success(service.ask(request)); }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<Void>> malformed() {
        return errors.handleBusinessException(new FgcBusinessException(FgcErrorCode.COMMON_002,"request",java.util.Map.of("field","request"),null));
    }
}
