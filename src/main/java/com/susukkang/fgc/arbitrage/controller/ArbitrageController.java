package com.susukkang.fgc.arbitrage.controller;

import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckSearchCondition;
import com.susukkang.fgc.arbitrage.dto.ArbitrageSearchResponse;
import com.susukkang.fgc.arbitrage.service.ArbitrageService;
import com.susukkang.fgc.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 설명 : 차익거래 검증 결과 조회 API를 제공한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/arbitrage-checks")
public class ArbitrageController {
    private final ArbitrageService arbitrageService;

    /**
     * 설명 : 검색 조건에 해당하는 차익거래 검증 결과와 요약 정보를 조회한다.
     *
     * @param condition 검색 조건
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @return 차익거래 검증 결과 요약 및 목록
     * @author hjKang
     * @since 2026-08-12
     */
    @GetMapping
    public ApiResponse<ArbitrageSearchResponse> getArbitrageChecks(
            @ModelAttribute ArbitrageCheckSearchCondition condition,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(arbitrageService.selectByCondition(condition, page, size));
    }
}
