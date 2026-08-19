package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.dto.ValidationRunRow;

/**
 * IF-API-48 검증 실행 기동 (FGC-FUN-042·043)
 */
public interface ValidationRunExecuteService {

    /**
     * validationRunId의 실행을 MonthlyValidationJob으로 비동기 기동한다.
     * 허용 상태는 CREATED뿐이다 — 1차는 자동 재시작이 없고(FUN-045는 2차),
     * FAILED 실행은 사람이 새 실행을 만든다(인터페이스정의서 §7-6 규칙 4).
     *
     * @param executedBy 실행 버튼을 누른 사용자(app_user.user_id) — 감사 추적 로그용.
     *                   JobParameters의 triggeredBy는 행의 생성자 값을 그대로 쓴다(멱등 계약).
     * @return guard를 통과한 시점의 행(status=CREATED)
     * @throws com.susukkang.fgc.common.exception.FgcBusinessException
     *         COMMON_004(404) 실행 미존재 · VRUN_003(409) FINALIZED ·
     *         VRUN_004(409) RUNNING/COMPLETED/FAILED
     */
    ValidationRunRow execute(Long validationRunId, Long executedBy, String requestId);
}
