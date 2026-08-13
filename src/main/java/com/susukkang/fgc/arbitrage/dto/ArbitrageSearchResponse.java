package com.susukkang.fgc.arbitrage.dto;

import com.susukkang.fgc.common.web.PageResponse;
import lombok.*;

/**
 * 설명 : ArbitrageSearchResponse
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Getter@Setter@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArbitrageSearchResponse {
    private ArbitrageCheckSummary summary; //요약 3종
    private PageResponse<ArbitrageCheckView> items; // 목록
}