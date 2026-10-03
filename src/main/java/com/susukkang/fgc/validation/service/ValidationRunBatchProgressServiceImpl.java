package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

// Repository가 돌려주는 "영향받은 행 수"를 보고 0건이면 예외로 바꿔주는 공통 처리만 담당
@Service
@RequiredArgsConstructor
public class ValidationRunBatchProgressServiceImpl implements ValidationRunBatchProgressService {

    private final ValidationRunRepository validationRunRepository;

    @Override
    @Transactional
    public void startRunning(Long validationRunId) {
        requireAffected(
                validationRunRepository.transitionToRunning(
                        validationRunId, ValidationRunStatus.CREATED, ValidationRunStatus.RUNNING),
                validationRunId);
    }

    @Override
    @Transactional
    public void advanceStep(Long validationRunId, int step) {
        // ①createRunStep은 startRunning()이 전담하므로 advanceStep은 ②~⑧(2~8)만 받는다.
        if (step < 2 || step > 8) {
            throw new IllegalArgumentException("advanceStep의 step은 2~8이어야 합니다: " + step);
        }
        requireAffected(
                validationRunRepository.updateCurrentStep(validationRunId, step, ValidationRunStatus.RUNNING),
                validationRunId);
    }

    @Override
    @Transactional
    public void completeRun(Long validationRunId) {
        requireAffected(
                validationRunRepository.transitionToCompleted(
                        validationRunId, ValidationRunStatus.RUNNING, ValidationRunStatus.COMPLETED),
                validationRunId);
    }

    @Override
    @Transactional
    public void markFailed(Long validationRunId, int failedStep, String failureMessage) {
        if (failedStep < 1 || failedStep > 8) {
            throw new IllegalArgumentException("markFailed의 failedStep은 1~8이어야 합니다: " + failedStep);
        }
        requireAffected(
                validationRunRepository.transitionToFailed(
                        validationRunId, failedStep, failureMessage,
                        ValidationRunStatus.RUNNING, ValidationRunStatus.FAILED),
                validationRunId);
    }

    // 4개 메서드가 부르는 Repository UPDATE는 전부 조건부 UPDATE다(ValidationRunRepository 참고)
    // 조건이 안 맞으면 DB가 0건을 돌려줌
    // UPDATE 자체는 실패하지 않고 "그냥 아무 행도 안 바뀐 성공"으로 보이기 때문에, 이렇게
    // affected 값을 직접 확인해야만 그 사실을 알아챌 수 있음
    private void requireAffected(int affected, Long validationRunId) {
        if (affected == 0) {
            throw new FgcBusinessException(FgcErrorCode.VRUN_005, Map.of("id", validationRunId));
        }
    }
}
