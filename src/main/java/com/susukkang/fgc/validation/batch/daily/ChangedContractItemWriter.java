package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.mapper.ContractStatusEventProcessingMapper;
import com.susukkang.fgc.validation.mapper.ExceptionCaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.util.List;

/**
 * changedContractStep의 Writer
 * : Processor 결과를 감사 흔적(contract_status_event_processing, exception_case)으로 남김
 */
@RequiredArgsConstructor
public class ChangedContractItemWriter implements ItemWriter<ChangedContractResult> {

    private final ContractStatusEventProcessingMapper contractStatusEventProcessingMapper;
    private final ExceptionCaseMapper exceptionCaseMapper;

    private Long validationRunId;

    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        this.validationRunId = ValidationRunBatchContext.getValidationRunId(
                stepExecution.getJobExecution().getExecutionContext());
    }

    @Override
    public void write(Chunk<? extends ChangedContractResult> chunk) {
        for (ChangedContractResult result : chunk) {
            // Processor가 조회한 스냅샷을 그대로 쓴다 — 여기서 다시 조회하면 Processor가
            // 결정을 내린 시점과 이 write() 시점 사이에 새로 들어온 이벤트까지 주워서
            // SUCCEEDED로 찍어버리는 TOCTOU 레이스가 생긴다(ChangedContractResult 참고).
            List<Long> pendingEventIds = result.pendingEventIds();

            if (result.success()) {
                // 성공 시엔 SUCCEEDED로 기록한다 — uq_cse_processing_succeeded 부분 유니크
                // 인덱스가 (event, job) 조합의 SUCCEEDED 중복을 막아주므로, 같은 이벤트가
                // 다음 날 다시 걸려도(예: reader가 updated_at 기준으로 또 잡는 경우) 여기서는
                // 조용히 무시되고 재작업이 일어나지 않는다.
                for (Long eventId : pendingEventIds) {
                    contractStatusEventProcessingMapper.insertProcessing(
                            eventId, DailyChangedContractJobNames.JOB_NAME, "SUCCEEDED", validationRunId, null);
                }
            } else {
                // 데이터 품질 문제로 스킵된 계약은 exception_case를 남기고, 이벤트는 FAILED로
                // 기록한다. FAILED는 SUCCEEDED와 달리 유니크 인덱스로 막혀있지 않으므로 —
                // 즉 이 이벤트는 다음 날 배치가 Reader의 NOT EXISTS(...SUCCEEDED) 조건에 걸려
                // 다시 후보로 잡힌다(재시도 가능 상태로 남겨두는 것이 의도).
                exceptionCaseMapper.insertDataQualityCase(
                        validationRunId,
                        result.contractId(),
                        "일일 변경 계약 재검증 실패",
                        result.failureReason());
                for (Long eventId : pendingEventIds) {
                    contractStatusEventProcessingMapper.insertProcessing(
                            eventId, DailyChangedContractJobNames.JOB_NAME, "FAILED",
                            validationRunId, result.failureReason());
                }
            }
        }
    }
}
