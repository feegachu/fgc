package com.susukkang.fgc.compare.controller;

import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.compare.dto.CompareCategoryResponse;
import com.susukkang.fgc.compare.dto.CompareExplainRequest;
import com.susukkang.fgc.compare.dto.CompareExplainResponse;
import com.susukkang.fgc.compare.dto.CompareRankingRequest;
import com.susukkang.fgc.compare.dto.CompareRankingResponse;
import com.susukkang.fgc.compare.service.CompareRankingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FUN-079 상품 비교·참고 순위 챗봇 API. 설계사(AGENT)만 호출할 수 있다.
 */
@Tag(name = "상품 비교 챗봇", description = "FUN-079 상품 비교·참고 순위 API")
@RestController
@RequestMapping("/api/v1/ai/compare-chat")
@RequiredArgsConstructor
public class CompareController {

    private final CompareRankingService compareRankingService;

    @Operation(summary = "비교 카테고리 목록", description = "챗봇 1단계에서 고르는 카테고리와 비교 가능한 보험사 수를 돌려준다.")
    @GetMapping("/categories")
    @PreAuthorize("hasRole('AGENT')")
    public ApiResponse<List<CompareCategoryResponse>> categories() {
        return ApiResponse.success(compareRankingService.categories());
    }

    @Operation(summary = "상품 비교 참고 순위", description = "카테고리와 선택 세부 조건(보험사·한도 초과 제외)을 받아 설계사 본인 기준 보수적 순수령액으로 상품 순위와 근거를 계산한다(LLM 없이 완성).")
    @PostMapping("/rank")
    @PreAuthorize("hasRole('AGENT')")
    public ApiResponse<CompareRankingResponse> rank(@Valid @RequestBody CompareRankingRequest request) {
        return ApiResponse.success(compareRankingService.rank(request.categoryCode(), request.filters()));
    }

    @Operation(summary = "순위 요약 문장", description = "서버가 보관한 순위 결과(rankResultId)를 풀어 쓴 요약을 돌려준다. 요약 실패는 200 + summaryError 로 알린다.")
    @PostMapping("/explain")
    @PreAuthorize("hasRole('AGENT')")
    public ApiResponse<CompareExplainResponse> explain(@Valid @RequestBody CompareExplainRequest request) {
        return ApiResponse.success(compareRankingService.explain(request.rankResultId()));
    }
}
