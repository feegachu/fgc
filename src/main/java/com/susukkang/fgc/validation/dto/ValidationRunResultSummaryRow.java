package com.susukkang.fgc.validation.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * VRUN-W02 ③건수·④결과 요약 4블록의 집계 투영 — 결과 테이블 5곳을 validation_run_id로
 * 스코프해 한 번에 센다(화면정의서 DASH-W01 "SQL을 여러 번 부르지 말고 묶어 받기"와 같은 취지).
 */
@Getter
@Setter
public class ValidationRunResultSummaryRow {
    private long targetSelectedCount;
    private long targetExcludedCount;
    private long targetReviewRequiredCount;
    private long capCheckedCount;
    private long capViolationCount;
    private long capWarningCount;
    private long capReviewRequiredCount;
    private long arbitrageCheckedCount;
    private long arbitrageCandidateCount;
    private long arbitrageReviewRequiredCount;
    private long journalCount;
    private long journalImbalanceCount;
    private long reconciliationResultCount;
    private long reconciliationMismatchCount;
    // FGC-FUN-043 확대 — 스케줄 생성상태, 대사 3분류(MATCHED/MISMATCHED/UNMATCHED).
    // 대사 3분류(코드리뷰 반영)는 ValidationRunDetailResponse.ReconciliationSummary로
    // 이어져 IF-API-47 응답에 노출된다 — "④ 결과 요약 4블록"에 이미 대사 블록이 있어서다.
    // scheduleGeneratedCount는 반대로 API 응답에 넣지 않는다 — 화면정의서
    // VRUN-W02(docs/FGC_화면정의서_v2_0.md:1416)의 "④ 결과 요약 4블록 — 1,200%/차익거래/
    // 원장/대사"에 스케줄 블록 자체가 없다. 이 필드는 ValidationRunSummaryPerformanceTest 등
    // 내부 정합성·성능 검증에서만 쓰는 값이다.
    private long scheduleGeneratedCount;
    private long reconciliationMatchedCount;
    private long reconciliationMismatchedCount;
    private long reconciliationUnmatchedCount;
    private BigDecimal reconciliationDifferenceAmountTotal;
}
