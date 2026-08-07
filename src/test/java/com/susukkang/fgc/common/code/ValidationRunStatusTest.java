package com.susukkang.fgc.common.code;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ValidationRunStatus.canTransitionTo()가 guard_run_lifecycle() 트리거의 전이표와
 * 일치하는지 검증한다. CREATED→FAILED는 트리거에 없으므로 허용하지 않는다(확인 완료).
 */
class ValidationRunStatusTest {

    @Test
    // CREATED는 RUNNING으로만 갈 수 있다
    void createdCanTransitionToAllowedTargets() {
        assertThat(ValidationRunStatus.CREATED.canTransitionTo(ValidationRunStatus.RUNNING)).isTrue();
    }

    @Test
    // RUNNING은 COMPLETED 또는 FAILED로 갈 수 있다
    void runningCanTransitionToAllowedTargets() {
        assertThat(ValidationRunStatus.RUNNING.canTransitionTo(ValidationRunStatus.COMPLETED)).isTrue();
        assertThat(ValidationRunStatus.RUNNING.canTransitionTo(ValidationRunStatus.FAILED)).isTrue();
    }

    @Test
    // FAILED는 RUNNING으로만 갈 수 있다(재시도)
    void failedCanOnlyTransitionToRunning() {
        assertThat(ValidationRunStatus.FAILED.canTransitionTo(ValidationRunStatus.RUNNING)).isTrue();
        assertThat(ValidationRunStatus.FAILED.canTransitionTo(ValidationRunStatus.COMPLETED)).isFalse();
    }

    @Test
    // COMPLETED는 FINALIZED로만 갈 수 있다
    void completedCanOnlyTransitionToFinalized() {
        assertThat(ValidationRunStatus.COMPLETED.canTransitionTo(ValidationRunStatus.FINALIZED)).isTrue();
        assertThat(ValidationRunStatus.COMPLETED.canTransitionTo(ValidationRunStatus.RUNNING)).isFalse();
    }

    @Test
    // FINALIZED는 최종 상태라 자기 자신을 포함해 어디로도 못 간다
    void finalizedCannotTransitionToAnything() {
        for (ValidationRunStatus target : ValidationRunStatus.values()) {
            assertThat(ValidationRunStatus.FINALIZED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    // 전이표에 없는 조합(CREATED→FAILED 포함)은 전부 false여야 한다
    void undefinedTransitionsAreRejected() {
        assertThat(ValidationRunStatus.CREATED.canTransitionTo(ValidationRunStatus.FAILED)).isFalse();
        assertThat(ValidationRunStatus.CREATED.canTransitionTo(ValidationRunStatus.COMPLETED)).isFalse();
        assertThat(ValidationRunStatus.CREATED.canTransitionTo(ValidationRunStatus.FINALIZED)).isFalse();
        assertThat(ValidationRunStatus.CREATED.canTransitionTo(ValidationRunStatus.CREATED)).isFalse();
        assertThat(ValidationRunStatus.RUNNING.canTransitionTo(ValidationRunStatus.CREATED)).isFalse();
        assertThat(ValidationRunStatus.RUNNING.canTransitionTo(ValidationRunStatus.FINALIZED)).isFalse();
    }
}
