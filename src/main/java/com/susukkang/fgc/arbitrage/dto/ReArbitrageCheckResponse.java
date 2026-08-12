package com.susukkang.fgc.arbitrage.dto;

import com.susukkang.fgc.common.code.ArbitrageCheckStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 설명 : 계약 단건 차익거래 수동 검증 결과를 반환한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReArbitrageCheckResponse {
    private Long arbitrageCheckId; // 차익거래 검증 결과 ID
    private ArbitrageCheckStatus resultStatus; // 판정 결과
    private Long validationRunId; // 수동 검증 실행 ID
}
