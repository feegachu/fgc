package com.susukkang.fgc.base.controller;

import com.susukkang.fgc.base.dto.OrganizationResponse;
import com.susukkang.fgc.base.dto.OrganizationSearchCriteria;
import com.susukkang.fgc.base.service.OrganizationService;
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

@Tag(name = "기준정보", description = "FGC 기준정보 조회 API")
@RestController
@RequestMapping("/api/v1/base/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;

    @Operation(
            summary = "GA·조직 기준정보 조회·선택",
            description = "기준일자에 적용되는 조직을 코드 또는 이름으로 검색합니다. "
                    + "includeHistorical=true이면 기간 밖 조직도 계약 검색용으로 반환합니다. 비활성 조직도 조회되며 신규 입력에서는 선택을 제한합니다."
    )
    @GetMapping
    @PreAuthorize(Roles.ANY_ROLE)
    public ApiResponse<PageResponse<OrganizationResponse>> search(
            @Parameter(description = "조직 코드 또는 조직명 검색어")
            @RequestParam(required = false)
            String keyword,
            @Parameter(description = "기준일자(yyyy-MM-dd)", example = "2026-08-11", required = true)
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf,
            @Parameter(description = "계약 목록 이력 검색 모드. 기간·활성 조건을 해제하며 입력 검증에는 사용하지 않습니다.")
            @RequestParam(defaultValue = "false")
            boolean includeHistorical,
            @Parameter(description = "정렬(key,asc|desc). organizationCode·organizationName·parentName·effectiveFrom·effectiveTo. 비우면 조직코드 오름차순")
            @RequestParam(required = false)
            String sort,
            @Parameter(description = "페이지(1-base)", example = "1")
            @RequestParam(defaultValue = "1")
            int page,
            @Parameter(description = "페이지 크기(최대 100)", example = "20")
            @RequestParam(defaultValue = "20")
            int size
    ) {
        OrganizationSearchCriteria criteria = new OrganizationSearchCriteria(keyword, asOf, includeHistorical, sort);
        return ApiResponse.success(organizationService.search(criteria, page, size));
    }
}
