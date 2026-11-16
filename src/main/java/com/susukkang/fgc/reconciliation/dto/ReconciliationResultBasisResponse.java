package com.susukkang.fgc.reconciliation.dto;

import java.util.List;

/** 검출 원본과 그 실행을 정정한 최신 완료 대사를 함께 보존한다. */
public record ReconciliationResultBasisResponse(
        ReconciliationResultDetailResponse detected,
        Long currentRunId,
        List<ReconciliationResultDetailResponse> currentResults
) {}
