package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.validation.dto.ValidationRunRow;

/**
 * validation_run의 상태 전이만 다루는 도메인 서비스(FUN-041 #1).
 * 실행 생성(INSERT)·Batch 실행·결과 반영은 후속 이슈에서 여기에 이어붙인다.
 */
public interface ValidationRunTransitionService {

    /**
     * validationRunId를 targetStatus로 전이시킨다.
     *
     * @throws com.susukkang.fgc.common.exception.FgcBusinessException
     *         (COMMON_004) 실행이 존재하지 않을 때,
     *         (VRUN_004) 허용되지 않은 전이일 때,
     *         (VRUN_005) 전이 자체는 허용되지만 조회 이후 다른 요청이 먼저 상태를 바꿔
     *         조건부 UPDATE가 0건일 때
     */
    ValidationRunRow transition(Long validationRunId, ValidationRunStatus targetStatus);
}
