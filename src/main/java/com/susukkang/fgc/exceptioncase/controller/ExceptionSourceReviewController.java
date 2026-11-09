package com.susukkang.fgc.exceptioncase.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.exceptioncase.dto.*;
import com.susukkang.fgc.exceptioncase.service.ExceptionSourceReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/exceptions/{id}/review")
@PreAuthorize(Roles.CAN_HANDLE_EXCEPTION)
@RequiredArgsConstructor
public class ExceptionSourceReviewController {
    private final ExceptionSourceReviewService service;
    @GetMapping
    public ApiResponse<ExceptionReviewContext> context(@PathVariable Long id, @AuthenticationPrincipal FgcUserDetails user) {
        return ApiResponse.success(service.context(id, user.getUserId()));
    }
    @PostMapping("/recheck")
    @PreAuthorize(Roles.CAN_PROCESS)
    public ApiResponse<ExceptionSourceRecheckResponse> recheck(@PathVariable Long id, @AuthenticationPrincipal FgcUserDetails user) {
        return ApiResponse.success(service.recheck(id, user.getUserId(), user.getUsername()));
    }
}
