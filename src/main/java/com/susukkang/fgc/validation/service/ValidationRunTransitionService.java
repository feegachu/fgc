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
     * 3) 2)는 통과했는데 조건부 UPDATE가 0건 — 그 사이 다른 요청이 상태를 바꿨거나
     *    실행이 삭제된 것. 0건일 때 findById로 재조회해 null이면 COMMON_004, 행이 있으면
     *    VRUN_005로 구분한다.
     *
     * @param validationRunId 전이 대상 validation_run.validation_run_id
     * @param targetStatus    요청한 다음 상태
     * @return 전이가 반영된 이후의 최신 행 — UPDATE 성공 뒤 재조회한 값이라 DB/트리거가 채운 컬럼까지 반영됨
     * @throws com.susukkang.fgc.common.exception.FgcBusinessException
     */
    ValidationRunRow transition(Long validationRunId, ValidationRunStatus targetStatus);
}
