package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ValidationRunTransitionServiceImpl implements ValidationRunTransitionService {

    private final ValidationRunMapper validationRunMapper;

    @Override
    @Transactional
    public ValidationRunRow transition(Long validationRunId, ValidationRunStatus targetStatus) {
        // TODO(FUN-041): 아래 순서로 구현한다 (To-do 체크리스트 참고).
        //
        // 1. validationRunMapper.findById(validationRunId)로 현재 행을 읽는다.
        //    없으면 FgcErrorCode.COMMON_004로 FgcBusinessException을 던진다
        //    (CapCheckController가 findDetail에서 하는 방식과 동일 — Map.of("id", validationRunId) 등).
        //
        // 2. row.getStatus()를 ValidationRunStatus.valueOf(...)로 바꾸고
        //    currentStatus.canTransitionTo(targetStatus)가 false면
        //    FgcErrorCode.VRUN_004로 던진다. params에 from/to를 넣어야
        //    messages.properties의 {from}/{to}가 채워진다.
        //
        // 3. validationRunMapper.updateStatusIfCurrent(validationRunId, currentStatus.name(),
        //    targetStatus.name())를 호출한다. 반환값이 0이면 — 2번에서 이미 허용된 전이라고
        //    확인했으므로 원인은 "그 사이 다른 요청이 먼저 상태를 바꿨다"뿐이다.
        //    FgcErrorCode.VRUN_005로 던진다.
        //
        // 4. 성공하면 findById로 다시 읽어 최신 행을 반환한다(또는 in-memory로 status만 바꿔 반환).
        //
        // 참고: FINALIZED는 canTransitionTo()가 이미 모든 target에 false를 주므로
        // "FINALIZED 상태 변경 차단"은 별도 분기 없이 2번에서 자연히 막힌다 — 그게 맞는 설계인지
        // 스스로 확인해 볼 것(단위테스트로 증명하기).
        throw new UnsupportedOperationException("TODO(FUN-041): 상태 전이 로직 구현 필요");
    }
}
