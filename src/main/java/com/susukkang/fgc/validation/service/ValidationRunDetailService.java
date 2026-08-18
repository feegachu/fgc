package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.dto.ValidationRunDetailResponse;
import com.susukkang.fgc.validation.dto.ValidationRunProgressResponse;

/**
 * IF-API-47 상세 · IF-API-49 진행률 조회 (FGC-FUN-042·043)
 */
public interface ValidationRunDetailService {

    /**
     * 헤더 + validation_target[] + 결과 요약 4블록.
     *
     * @throws com.susukkang.fgc.common.exception.FgcBusinessException COMMON_004(404) 실행 미존재
     */
    ValidationRunDetailResponse detail(Long validationRunId);

    /**
     * validation_run.current_step 기반 진행률 — batch_step_execution을 읽지 않는다.
     *
     * @throws com.susukkang.fgc.common.exception.FgcBusinessException COMMON_004(404) 실행 미존재
     */
    ValidationRunProgressResponse progress(Long validationRunId);
}
