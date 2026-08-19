package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.batch.MonthlyValidationJobTrigger;
import com.susukkang.fgc.validation.batch.daily.DailyChangedContractJobTrigger;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ValidationRunExecuteServiceImpl implements ValidationRunExecuteService {

    private final ValidationRunMapper validationRunMapper;
    private final MonthlyValidationJobTrigger monthlyValidationJobTrigger;
    private final DailyChangedContractJobTrigger dailyChangedContractJobTrigger;

    @Override
    public ValidationRunRow execute(Long validationRunId, Long executedBy, String requestId) {
        ValidationRunRow row = validationRunMapper.findById(validationRunId);
        if (row == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_004);
        }
        if (ValidationRunStatus.FINALIZED.name().equals(row.getStatus())) {
            // "수정 불가 — 확정된 검증 결과입니다. 새 실행을 만드세요." (§3-2)
            throw new FgcBusinessException(FgcErrorCode.VRUN_003);
        }
        if (!ValidationRunStatus.CREATED.name().equals(row.getStatus())) {
            // RUNNING·COMPLETED·FAILED — 1차는 재기동을 지원하지 않는다.
            throw new FgcBusinessException(FgcErrorCode.VRUN_004,
                    Map.of("from", row.getStatus(), "to", ValidationRunStatus.RUNNING.name()));
        }

        // JobParameters의 triggeredBy는 행 생성자 값이라, 실행 버튼을 누른 사용자는
        // 여기서 requestId와 함께 남긴다 — requestId로 배치 감사행과 이어진다.
        log.info("검증 실행 기동 요청 (validationRunId={}, executedBy={}, requestId={})",
                validationRunId, executedBy, requestId);

        // join하지 않는다 — IF-API-48은 202 즉시 반환, 진행은 IF-API-49 폴링이 본다.
        // runType별로 서로 다른 배치를 기동한다 — MANUAL_CONTRACT를 MonthlyValidationJob에
        // 잘못 태우면 안 된다(#XXX 코드리뷰 반영). DailyChangedContractJobTrigger는 행을 직접
        // 받지 않고 "오늘의 MANUAL_CONTRACT 실행"을 스스로 조회/재사용하므로(CreateDailyRunTasklet
        // 이 created_at 기준으로 판정) row.getTriggeredBy()만 넘기면 이 행을 그대로 이어 탄다.
        try {
            if (ValidationRunType.MANUAL_CONTRACT.name().equals(row.getRunType())) {
                dailyChangedContractJobTrigger.runManual(row.getTriggeredBy(), requestId);
            } else {
                monthlyValidationJobTrigger.launch(row, requestId);
            }
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 트리거 대기열 포화(AbortPolicy) — 500 대신 재시도 안내가 있는 409 로 돌려준다.
            // VRUN_005("다시 조회 후 시도하세요")가 기존 코드 중 재시도 의미에 가장 가깝다.
            throw new FgcBusinessException(FgcErrorCode.VRUN_005);
        }
        return row;
    }
}
