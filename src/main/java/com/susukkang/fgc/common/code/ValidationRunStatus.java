package com.susukkang.fgc.common.code;

/**
 * validation_run.status 의 자바측 표현.
 *
 * DB 쪽 진짜 규칙은 fgc.guard_run_lifecycle() 트리거(V7__run_progress_and_snapshot_locks.sql)에
 * 있다. 이 트리거가 최종 방어선이고, 여기 canTransitionTo()는 그 앞단에서 같은 규칙을
 * 미리 걸러 "업무 예외(FgcBusinessException)"로 바꿔주는 역할이다 — 트리거까지 가서
 * RAISE EXCEPTION으로 죽으면 사용자에게 SQL 에러가 그대로 노출된다.
 *
 * 그래서 아래 canTransitionTo()가 허용하는 전이는 트리거의 이 부분과 반드시 동일해야 한다:
 *
 *   (OLD.status = 'CREATED'   AND NEW.status = 'RUNNING') OR
 *   (OLD.status = 'RUNNING'   AND NEW.status IN ('COMPLETED','FAILED')) OR
 *   (OLD.status = 'COMPLETED' AND NEW.status = 'FINALIZED') OR
 *   (OLD.status = 'FAILED'    AND NEW.status = 'RUNNING')
 *
 * FINALIZED에서 나가는 전이는 어디에도 없다 — 최종 상태다.
 */
public enum ValidationRunStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
    FINALIZED;

    /**
     * this(현재 상태) → target(요청한 다음 상태) 전이가 허용되는지.
     *
     * TODO(FUN-041): guard_run_lifecycle()의 전이표를 그대로 옮겨 구현한다.
     *   힌트: switch (this) 로 현재 상태별 허용 target 집합을 나열하는 방식이
     *   위 트리거 주석과 1:1로 비교하기 가장 쉽다.
     */
    public boolean canTransitionTo(ValidationRunStatus target) {
        throw new UnsupportedOperationException("TODO(FUN-041): 전이표 구현 필요");
    }
}
