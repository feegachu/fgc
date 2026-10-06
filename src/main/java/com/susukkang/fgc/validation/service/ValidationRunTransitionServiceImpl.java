package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.entity.ValidationRun;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ValidationRunTransitionServiceImpl implements ValidationRunTransitionService {

    private final ValidationRunRepository validationRunRepository;

    @Override
    @Transactional
    public ValidationRunRow transition(Long validationRunId, ValidationRunStatus targetStatus) {
        // validationRun이 null 확인
        ValidationRun run = validationRunRepository.findById(validationRunId)
                .orElseThrow(() -> new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", validationRunId)));

        // 전이 가능 여부 판정
        ValidationRunStatus currentStatus = run.getStatus();
        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new FgcBusinessException(FgcErrorCode.VRUN_004,
                    Map.of("from", currentStatus, "to", targetStatus));
        }

        int affected = validationRunRepository.updateStatusIfCurrent(validationRunId, currentStatus, targetStatus);
        if (affected == 0) {
            // 0건은 "그 사이 삭제됨"과 "그 사이 상태만 바뀜"을 둘 다 가리킬 수 있어 재조회로 구분한다.
            if (validationRunRepository.findById(validationRunId).isEmpty()) {
                throw new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", validationRunId));
            }
            throw new FgcBusinessException(FgcErrorCode.VRUN_005, Map.of("id", validationRunId));
        }
        // 메모리 객체를 고쳐서 반환하면 DB/트리거가 채운 컬럼을 놓치므로 다시 조회해서 반환.
        // updateStatusIfCurrent가 clearAutomatically=true라 이 조회는 1차 캐시가 아니라 DB를 다시 읽는다.
        ValidationRun updated = validationRunRepository.findById(validationRunId)
                .orElseThrow(() -> new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", validationRunId)));
        return ValidationRunRow.from(updated);
    }
}
