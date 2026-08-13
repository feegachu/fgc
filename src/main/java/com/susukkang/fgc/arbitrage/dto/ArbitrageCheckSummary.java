package com.susukkang.fgc.arbitrage.dto;

import lombok.*;

/**
 * 설명 : 차익거래 검색 결과 이상없음,검토대상,자료부족 count
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArbitrageCheckSummary {
    private long clearCount; //이상없음
    private long candidateCount; //검토대상
    private long reviewRequiredCount; //자료부족

    public long getTotalArbitrageChecks() {
        return clearCount + candidateCount + reviewRequiredCount;
    }
}