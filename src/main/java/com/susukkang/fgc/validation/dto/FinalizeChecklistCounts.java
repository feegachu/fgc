package com.susukkang.fgc.validation.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** IF-API-50의 여섯 확정 조건별 실패 건수 조회 결과. */
@Getter
@Setter
public class FinalizeChecklistCounts {
    private Long validationRunId;
    private LocalDate validationMonth;
    private long incompleteRunCount;
    private long journalImbalanceCount;
    private long unresolvedCriticalExceptionCount;
    private long unresolvedPolicyExceptionCount;
    private long attributionImbalanceCount;
    private long capDetailMismatchCount;
}
