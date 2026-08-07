package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.validation.dto.ValidationRunRow;

/**
 * validation_run의 상태 전이만 다루는 서비스
 * "지금 상태 → 요청한 상태"가 허용되는 전이인지 판단하고, 허용될 때만 DB에 반영
 */
public interface ValidationRunTransitionService {

    /**
     * validationRunId가 가리키는 실행을 targetStatus로 전이
     *
     * 1) 실행 자체가 X
     * 2) 실행은 있는데, 그 현재 상태에서 targetStatus로 가는 것은 규칙 위반
     *
     * 3) 2)는 통과했는데(허용된 전이인데), findById와 UPDATE 사이에 다른 요청이 먼저
     *    상태를 바꿔버려서 조건부 UPDATE가 0건을 반환
     *
     * @param validationRunId 전이 대상 validation_run.validation_run_id
     * @param targetStatus    요청한 다음 상태
     * @return 전이가 반영된 이후의 최신 행
     * @throws com.susukkang.fgc.common.exception.FgcBusinessException
     */
    ValidationRunRow transition(Long validationRunId, ValidationRunStatus targetStatus);
}
