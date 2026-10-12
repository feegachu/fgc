package com.susukkang.fgc.validation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** IF-API-50의 여덟 확정 조건별 실패 건수 조회 결과. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FinalizeChecklistCounts {
    private Long validationRunId;
    private LocalDate validationMonth;
    private long incompleteRunCount;
    private long journalImbalanceCount;
    private long unresolvedCriticalExceptionCount;
    private long unresolvedPolicyExceptionCount;
    private long attributionImbalanceCount;
    private long capDetailMismatchCount;
    /** 같은 검증월에 이 실행보다 뒤에 만든 월 전체 실행 수(조건 7, #491). */
    private long supersededRunCount;
    /** 그중 가장 최근 실행 ID. 없으면 null — 조건 7의 바로가기 대상. */
    private Long latestRunId;
    /** 같은 검증월에 이미 FINALIZED 인 다른 실행 수(조건 8, #494). */
    private long finalizedRunCount;
    /** 그중 가장 최근 확정 실행 ID. 없으면 null — 조건 8의 바로가기 대상. */
    private Long finalizedRunId;
}
