package com.susukkang.fgc.audit.controller;

import com.susukkang.fgc.audit.dto.AuditLogResponse;
import com.susukkang.fgc.audit.service.AuditLogQueryService;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * FUN-061 IF-API-52 감사로그 조회 API.
 * audit_log 는 append-only 라 이 컨트롤러는 조회 외 어떤 쓰기 엔드포인트도 제공하지 않는다.
 */
@Tag(name = "감사로그", description = "FUN-061 핵심 업무 감사로그 조회 API")
@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    @Operation(
            summary = "감사로그 조회",
            description = "누가 언제 무엇을 바꿨는지 검색합니다. "
                    + "userLoginId 가 null 인 행은 배치 발 감사행(화면 표기 BATCH)입니다."
    )
    @GetMapping
    // SecurityConfig 의 URL 규칙과 이중화 — 컨트롤러를 우회하는 경로가 생겨도 조회 권한을 지킨다 (FUN-002)
    @PreAuthorize(Roles.CAN_VIEW_AUDIT_LOG)
    public ApiResponse<PageResponse<AuditLogResponse>> search(
            @Parameter(description = "대상 종류", example = "COMMISSION_PAYMENT")
            @RequestParam(required = false)
            String entityType,
            @Parameter(description = "대상 ID", example = "42")
            @RequestParam(required = false)
            String entityId,
            @Parameter(description = "행위자 사용자 ID", example = "12")
            @RequestParam(required = false)
            Long userId,
            @Parameter(description = "행위 종류", example = "PAYMENT_CONFIRMED")
            @RequestParam(required = false)
            String action,
            @Parameter(description = "조회 시작일(포함)", example = "2026-08-01")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @Parameter(description = "조회 종료일(포함)", example = "2026-08-16")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @Parameter(description = "페이지(1-base)", example = "1")
            @RequestParam(defaultValue = "1")
            int page,
            @Parameter(description = "페이지 크기(최대 100)", example = "20")
            @RequestParam(defaultValue = "20")
            int size
    ) {
        return ApiResponse.success(
                auditLogQueryService.search(entityType, entityId, userId, action, from, to, page, size));
    }
}
