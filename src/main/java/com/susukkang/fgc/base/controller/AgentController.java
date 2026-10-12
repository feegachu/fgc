package com.susukkang.fgc.base.controller;

import com.susukkang.fgc.base.dto.AgentResponse;
import com.susukkang.fgc.base.dto.AgentSearchCriteria;
import com.susukkang.fgc.base.service.AgentService;
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
@RequestMapping("/api/v1/base/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @Operation(
            summary = "보험설계사 기준정보 조회·선택 (IF-API-07)",
            description = "기준일에 위촉 상태가 유효한 설계사를 조직·코드·이름으로 검색합니다. "
                    + "계약 이력 조회에서 includeTerminated=true를 지정하면 기준일 이전에 해촉된 설계사도 포함합니다. "
                    + "agentStatus와 activeYn은 신규 입력 선택 가능 여부를 판단할 때 사용합니다."
    )
    @GetMapping
    @PreAuthorize(Roles.ANY_ROLE)
    public ApiResponse<PageResponse<AgentResponse>> search(
            @Parameter(description = "소속 조직 ID")
            @RequestParam(required = false)
            Long organizationId,
            @Parameter(description = "설계사 코드 또는 이름 검색어")
            @RequestParam(required = false)
            String keyword,
            @Parameter(description = "위촉 유효성 기준일(yyyy-MM-dd)", example = "2026-08-11", required = true)
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf,
            @Parameter(description = "기준일 이전 해촉 설계사 포함 여부(계약 이력 검색용, 위촉일은 기준일 이하로 유지)")
            @RequestParam(defaultValue = "false")
            boolean includeTerminated,
            @Parameter(description = "페이지(1-base)", example = "1")
            @RequestParam(defaultValue = "1")
            int page,
            @Parameter(description = "페이지 크기(최대 100)", example = "20")
            @RequestParam(defaultValue = "20")
            int size
    ) {
        AgentSearchCriteria criteria = new AgentSearchCriteria(organizationId, keyword, asOf, includeTerminated);
        return ApiResponse.success(agentService.search(criteria, page, size));
    }
}
