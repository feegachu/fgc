package com.susukkang.fgc.assistant.controller;

import com.susukkang.fgc.assistant.dto.*;
import com.susukkang.fgc.assistant.service.PicoGuideStartService;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ai/pico/guides")
@PreAuthorize(Roles.ANY_ROLE)
public class PicoGuideController {
    private final PicoGuideStartService service;
    @PostMapping("/prepare")
    @Operation(summary="현재 저장 지급 가이드의 일회성 시작 준비", description="첫 scope 강조 전용. 금융 승인/진도 저장/외부 모델 호출 없음.")
    public ApiResponse<PicoGuideResponse> prepare(@Valid @RequestBody PicoGuideRequest request, @RequestHeader("Authorization") String bearer) {
        return ApiResponse.success(service.prepare(request, bearer));
    }
    @PostMapping("/start")
    @Operation(summary="사용자 선택 후 지급 저장본을 재검증해 가이드 시작", description="동일 사용자·Bearer·역할·저장본·카탈로그·revision·5분 만료 및 일회성 검증.")
    public ApiResponse<PicoGuideResponse> start(@Valid @RequestBody PicoGuideRequest request, @RequestHeader("Authorization") String bearer) {
        return ApiResponse.success(service.start(request, bearer));
    }
}
