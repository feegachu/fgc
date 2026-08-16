package com.susukkang.fgc.base.controller;

import com.susukkang.fgc.base.dto.InsurerResponse;
import com.susukkang.fgc.base.service.InsurerService;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "기준정보", description = "FGC 기준정보 조회 API")
@RestController
@RequestMapping("/api/v1/base/insurers")
@RequiredArgsConstructor
public class InsurerController {

    private final InsurerService insurerService;

    @Operation(
            summary = "보험회사(원수사) 기준정보 조회·선택",
            description = "보험회사를 코드 또는 이름으로 검색합니다. "
                    + "사용중지된 보험회사도 반환되며 activeYn=false인 항목은 신규 입력에서 선택할 수 없습니다."
    )
    @GetMapping
    @PreAuthorize(Roles.ANY_ROLE)
    public ApiResponse<PageResponse<InsurerResponse>> search(
            @Parameter(description = "보험회사 코드 또는 보험회사명 검색어")
            @RequestParam(required = false)
            String keyword,
            @Parameter(description = "페이지(1-base)", example = "1")
            @RequestParam(defaultValue = "1")
            int page,
            @Parameter(description = "페이지 크기(최대 100)", example = "20")
            @RequestParam(defaultValue = "20")
            int size
    ) {
        return ApiResponse.success(insurerService.search(keyword, page, size));
    }
}
