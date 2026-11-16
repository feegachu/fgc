package com.susukkang.fgc.exceptioncase.controller;

import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCapBasisResponse;
import com.susukkang.fgc.exceptioncase.service.ExceptionCapBasisService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/exceptions/{id}/cap-basis")
@RequiredArgsConstructor
@PreAuthorize(Roles.ANY_ROLE)
public class ExceptionCapBasisController {
    private final ExceptionCapBasisService service;
    @GetMapping
    public ApiResponse<ExceptionCapBasisResponse> find(@PathVariable Long id) {
        return ApiResponse.success(service.find(id));
    }
}
