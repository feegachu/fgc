package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.contract.repository.InsuranceContractRepository;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.mapper.ContractStatusEventProcessingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.ItemProcessor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * changedContractStep의 Processor: 계약 1건을 다시 검증
 * 스케줄 재생성은 무조건 호출X - "예상 지급 스케줄은 계약·정책·상태 변경으로 재생성이 필요한 경우에만 영향 범위에서 호출합니다" (IF-BAT-02)
 *
 * 차익거래 검증 및 원장 불균형 재검증은 로직 구현 후 추가 예정
 */
@RequiredArgsConstructor
public class ChangedContractItemProcessor implements ItemProcessor<Long, ChangedContractResult> {

    private final InsuranceContractRepository insuranceContractRepository;
    private final ScheduleService scheduleService;
    private final CapCheckService capCheckService;
    private final ContractStatusEventProcessingMapper contractStatusEventProcessingMapper;

    private Long validationRunId;
    private OffsetDateTime lastProcessedAt;

    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        var jobExecutionContext = stepExecution.getJobExecution().getExecutionContext();
        this.validationRunId = ValidationRunBatchContext.getValidationRunId(jobExecutionContext);
        this.lastProcessedAt = DailyBatchContext.getLastProcessedAt(jobExecutionContext);
    }

    @Override
    public ChangedContractResult process(Long contractId) {
        List<Long> pendingEventIds = contractStatusEventProcessingMapper.findPendingEventIds(
                contractId, DailyChangedContractJobNames.JOB_NAME);

        try {
            InsuranceContract contract = insuranceContractRepository
                    .findById(contractId)
                    .orElse(null);

            if (contract == null) {
                return ChangedContractResult.dataQualitySkip(
                        contractId,
                        "계약을 찾을 수 없음: " + contractId,
                        pendingEventIds
                );
            }

            boolean contractItselfChanged = contract.getUpdatedAt() != null
                    && lastProcessedAt != null
                    && contract.getUpdatedAt().isAfter(lastProcessedAt);
            if (!pendingEventIds.isEmpty() || contractItselfChanged) {
                // REQUIRES_NEW — 이 계약만 실패해도 청크(다른 계약들)의 트랜잭션을
                // rollback-only로 오염시키지 않는다(아래 catch의 skip이 실제로 동작하려면 필수).
                scheduleService.generateSchedulesInNewTransaction(contract);
            }

            LocalDate asOfDate = LocalDate.now(DateUtil.SEOUL_ZONE);
            for (PaymentStage paymentStage : PaymentStage.values()) {
                capCheckService.calculateAndSave(
                        CapCalculationCommand.dailyBatch(contractId, paymentStage, asOfDate, validationRunId));
            }

            return ChangedContractResult.success(contractId, pendingEventIds);
        } catch (FgcBusinessException e) {
            return ChangedContractResult.dataQualitySkip(contractId, describe(e), pendingEventIds);
        }
    }

    private String describe(FgcBusinessException e) {
        String code = e.getErrorCode().getCode();
        return e.getDetail() == null ? code : code + ": " + e.getDetail();
    }
}
