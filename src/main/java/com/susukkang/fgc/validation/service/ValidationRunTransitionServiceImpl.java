package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ValidationRunTransitionServiceImpl implements ValidationRunTransitionService {

    private final ValidationRunMapper validationRunMapper;

    @Override
    @Transactional
    public ValidationRunRow transition(Long validationRunId, ValidationRunStatus targetStatus) {
        // validationRunRow가 null 확인
        ValidationRunRow validationRunRow = validationRunMapper.findById(validationRunId);
        if (validationRunRow==null){
            throw new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", validationRunId));
        }

        // 전이 가능 여부 판정
        ValidationRunStatus currentStatus = ValidationRunStatus.valueOf(validationRunRow.getStatus());
        if(!currentStatus.canTransitionTo(targetStatus)){
            throw new FgcBusinessException(FgcErrorCode.VRUN_004,
                    Map.of("from", currentStatus, "to", targetStatus));
        }

        int affected = validationRunMapper.updateStatusIfCurrent(
                validationRunId, currentStatus.name(), targetStatus.name());
        if(affected==0){
            throw new FgcBusinessException(FgcErrorCode.VRUN_005, Map.of("id", validationRunId));
        }

        // 최신 행 반환 — UPDATE로 바뀐 status를 반영해야 한다(전이 전 값을 그대로
        //돌려주면 호출자가 여전히 CREATED/RUNNING 등 옛 상태를 보게 된다).
        validationRunRow.setStatus(targetStatus.name());
        return validationRunRow;
    }
}
