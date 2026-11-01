package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.dto.ValidationRunRow;

/**
 * IF-API-48 검증 실행 기동 (FGC-FUN-042·043)
 */
public interface ValidationRunExecuteService {

    /**
     * validationRunId의 실행을 MonthlyValidationJob으로 비동기 기동한다.
     * CREATED는 신규 기동, FAILED인 월 실행은 기존 JobInstance의 실패 Step부터 재시작한다.
     * MANUAL_CONTRACT의 FAILED는 월 재시작 경로로 보내지 않는다.
     *
     * @param executedBy 실행 버튼을 누른 사용자(app_user.user_id) — 감사 추적 로그용.
     *                   JobParameters의 triggeredBy는 행의 생성자 값을 그대로 쓴다(멱등 계약).
     * @return guard를 통과한 시점의 행(status=CREATED 또는 FAILED). 진행 상태는 폴링으로 확인한다.
     * @throws com.susukkang.fgc.common.exception.FgcBusinessException
     *         COMMON_004(404) 실행 미존재 · VRUN_003(409) FINALIZED ·
     *         VRUN_004(409) 허용 상태 아님 · VRUN_005(409) 재시작 이력 확인 불가 또는 경합
     */
    ValidationRunRow execute(Long validationRunId, Long executedBy, String requestId);
}
