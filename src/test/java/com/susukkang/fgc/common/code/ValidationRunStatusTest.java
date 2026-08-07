package com.susukkang.fgc.common.code;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * ValidationRunStatus.canTransitionTo()가 fgc.guard_run_lifecycle() 트리거의 전이표와
 * 정확히 같은지 검증한다(DB 없이 순수 단위테스트).
 *
 * 전이표(V7__run_progress_and_snapshot_locks.sql의 guard_run_lifecycle 참고):
 *   CREATED   → RUNNING
 *   CREATED   → FAILED
 *   RUNNING   → COMPLETED
 *   RUNNING   → FAILED
 *   FAILED    → RUNNING
 *   COMPLETED → FINALIZED
 *   그 외 전부 불가, FINALIZED는 어디로도 못 감
 *
 * 주의: 이슈 설명의 허용 목록에는 CREATED → FAILED 가 없지만, 트리거 주석에는
 * 명시돼 있지 않다 — 실제 트리거 코드(IF NOT (...))를 다시 읽고 어느 쪽이 진짜인지
 * 확인한 뒤 테스트를 작성할 것. 이 파일의 목적 자체가 "코드와 DB가 어긋나지 않는지"
 * 증명하는 것이므로, 여기서 발견한 불일치가 있다면 그게 이 이슈의 진짜 산출물이다.
 */
class ValidationRunStatusTest {

    @Test
    @Disabled("TODO(FUN-041): CREATED에서 허용된 모든 target이 true인지 확인")
    void createdCanTransitionToAllowedTargets() {
    }

    @Test
    @Disabled("TODO(FUN-041): RUNNING에서 허용된 모든 target이 true인지 확인")
    void runningCanTransitionToAllowedTargets() {
    }

    @Test
    @Disabled("TODO(FUN-041): FAILED에서 RUNNING으로만 갈 수 있는지 확인")
    void failedCanOnlyTransitionToRunning() {
    }

    @Test
    @Disabled("TODO(FUN-041): COMPLETED에서 FINALIZED로만 갈 수 있는지 확인")
    void completedCanOnlyTransitionToFinalized() {
    }

    @Test
    @Disabled("TODO(FUN-041): FINALIZED에서는 어떤 target으로도 못 가는지(자기 자신 포함) 확인")
    void finalizedCannotTransitionToAnything() {
    }

    @Test
    @Disabled("TODO(FUN-041): 정의되지 않은 조합(예: CREATED->COMPLETED, CREATED->FINALIZED)이 전부 false인지 확인")
    void undefinedTransitionsAreRejected() {
    }
}
