package com.susukkang.fgc.validation.dto;

import lombok.Getter;
import lombok.Setter;

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
}
