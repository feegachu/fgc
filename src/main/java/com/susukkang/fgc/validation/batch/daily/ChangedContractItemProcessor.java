package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.arbitrage.service.ArbitrageCheckBatchItemService;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.contract.repository.ContractStatusEventRepository;
import com.susukkang.fgc.contract.repository.InsuranceContractRepository;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.repository.ContractStatusEventProcessingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.ItemProcessor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * changedContractStep의 Processor: 계약 1건을 다시 검증
 * 스케줄 재생성은 무조건 호출X - "예상 지급 스케줄은 계약·정책·상태 변경으로 재생성이 필요한 경우에만 영향 범위에서 호출합니다" (IF-BAT-02)
 *
 * 차익거래는 변경된 계약 전부를 월 실행·수동 재검증과 같은 계산 경로(ArbitrageCheckBatchItemService)로 점검한다 —
 * 인터페이스정의서 6-4 "차익거래 검증" 행의 일일 변경분 열이 O다(#512).
 * 원장 불균형 재검사는 로직 구현 후 추가 예정
 */
@RequiredArgsConstructor
public class ChangedContractItemProcessor implements ItemProcessor<Long, ChangedContractResult> {

    private final InsuranceContractRepository insuranceContractRepository;
    private final ScheduleService scheduleService;
    private final CapCheckService capCheckService;
    private final ContractStatusEventProcessingRepository contractStatusEventProcessingRepository;
    private final ArbitrageCheckBatchItemService arbitrageCheckBatchItemService;
    private final ContractStatusEventRepository contractStatusEventRepository;

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
        List<Long> pendingEventIds = contractStatusEventProcessingRepository.findPendingEventIds(
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

            // 1,200%와 차익거래는 서로의 결과를 읽지 않는 독립 검증이다. 월 실행에서 Step이 나뉘어
            // 한쪽 실패가 다른 쪽을 막지 않는 것(CapCheckBatchAdapter·ArbitrageCheckBatchAdapter)과 같게,
            // 1,200% 룰셋이 없는 계약(CAP_004, 2026-07 이전 계약)도 차익거래는 점검한다(#512).
            // 어느 한쪽이라도 실패하면 스킵으로 돌려 상태 사건을 FAILED로 남긴다(다음 날 Reader가 다시 잡는다).
            // 단, CAP_004(룰셋 없음)는 실패가 아니다(#522, 아래 단계 루프 참고).
            List<String> failures = new ArrayList<>();
            LocalDate asOfDate = LocalDate.now(DateUtil.SEOUL_ZONE);
            // 순서 주의: 차익거래를 1,200%보다 먼저 부른다. 1,200%(CapCheckServiceImpl)는 이 청크 트랜잭션에서
            // 계약 행을 FOR UPDATE로 잠가 청크 커밋까지 쥐고 있는데, 그 뒤에 REQUIRES_NEW(다른 연결)로
            // arbitrage_check를 넣으면 FK 검사가 그 잠금을 기다리고 이 스레드는 차익거래를 기다려 끝없이 멈춘다
            // (PostgreSQL은 교착으로 감지하지 못한다). ChangedContractArbitrageIntegrationTest가 이 순서를 지킨다.
            try {
                // REQUIRES_NEW — 실패하면 이 계약의 차익거래 결과·예외는 함께 롤백되어 남지 않는다.
                arbitrageCheckBatchItemService.process(validationRunId, contractId, arbitrageAsOfDate(contract, asOfDate));
            } catch (FgcBusinessException e) {
                failures.add(describe(e));
            }
            for (PaymentStage paymentStage : PaymentStage.values()) {
                try {
                    capCheckService.calculateAndSave(
                            CapCalculationCommand.dailyBatch(contractId, paymentStage, asOfDate, validationRunId));
                } catch (FgcBusinessException e) {
                    if (e.getErrorCode() != FgcErrorCode.CAP_004) {
                        failures.add(describe(e));
                        continue;
                    }
                    // 룰셋 없음은 그 단계가 1,200% 대상이 아니라는 판정이다(예: 2026-07 이전 계약의 GA→FC,
                    // REG-09 ①⑤ 2026.7.1·REG-19 계약일 기준). 실패로 돌리면 사건이 FAILED로 남아 매일
                    // 재처리되므로(#522), 계약 저장 경로(ContractServiceImpl)처럼 검토 큐에만 남긴다.
                    // 검토 건은 계약·단계·유형 업무키 UPSERT라 다시 처리돼도 한 건으로 유지된다.
                    // calculateAndSave가 noRollbackFor라 청크 트랜잭션은 rollback-only가 되지 않는다.
                    scheduleService.registerCapRuleReview(contractId, paymentStage,
                            e.getDetail() == null ? "적용 가능한 1,200% 룰셋이 없습니다." : e.getDetail());
                }
            }

            return failures.isEmpty()
                    ? ChangedContractResult.success(contractId, pendingEventIds)
                    : ChangedContractResult.dataQualitySkip(contractId, String.join(" / ", failures), pendingEventIds);
        } catch (FgcBusinessException e) {
            return ChangedContractResult.dataQualitySkip(contractId, describe(e), pendingEventIds);
        }
    }

    /**
     * 해지·실효 계약은 효력일(Asia/Seoul 날짜)을 기준일로 쓴다 — "해지시점까지 지급한 수수료등 … 해지시점까지
     * 실제로 납입한 보험료"(감독규정 제4-32조⑥), 실효는 "실효계약 사유 발생 시를 계약해지 시기로 판단"(FAQ 2차 Q12).
     * 효력일이 고정이라 같은 해지 사건을 며칠 뒤 다시 읽어도 같은 기준일 행이 된다.
     * 최근 사건의 상태가 현재 상태와 다르면(사건 없는 수기 상태 변경) 효력일을 알 수 없으므로 오늘을 쓴다.
     * "현재 상태와 같은 사건 중 최근 것"을 쓰지 않는 이유: 실효 → 부활(사건) → 수기로 다시 실효처럼 더 늦은 사건이
     * 그 상태를 끝낸 경우, 부활로 효력을 잃은 예전 실효일을 고르게 된다(#515 조건 ③과 같은 판단).
     */
    private LocalDate arbitrageAsOfDate(InsuranceContract contract, LocalDate today) {
        ContractStatus status = contract.getCurrentStatus();
        if (status != ContractStatus.TERMINATED && status != ContractStatus.LAPSED) {
            return today;
        }
        return contractStatusEventRepository
                .findFirstByContractIdOrderByEffectiveAtDescEventSeqDesc(contract.getContractId())
                .filter(event -> event.getNewStatus() == status)
                .map(event -> event.getEffectiveAt().atZoneSameInstant(DateUtil.SEOUL_ZONE).toLocalDate())
                .orElse(today);
    }

    private String describe(FgcBusinessException e) {
        String code = e.getErrorCode().getCode();
        return e.getDetail() == null ? code : code + ": " + e.getDetail();
    }
}
