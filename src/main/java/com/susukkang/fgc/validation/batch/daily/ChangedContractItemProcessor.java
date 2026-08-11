package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.contract.dto.InsuranceContract;
import com.susukkang.fgc.contract.mapper.ContractMapper;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.mapper.ContractStatusEventProcessingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.ItemProcessor;

import java.time.LocalDate;
import java.util.List;

/**
 * changedContractStep의 Processor: 계약 1건을 다시 검증
 *
 * ★★★ 미구현 범위 (2026-08-11 코드리뷰로 재확인, 별도 이슈 없이 이 주석으로 추적) ★★★
 * IF-BAT-02 §7-5 "대상 검증"은 1차 범위를 "1,200% 누적 · 차익거래 · 원장 불균형 재검사"
 * 세 가지로 명시한다(docs/05_인터페이스정의서_v2_0.md:534). 이 Processor는 그중 1,200%
 * (capCheckService)만 실제로 수행한다 — 차익거래 재검사와 원장 불균형 재검사는 아예 호출되지
 * 않는다.
 *
 * 이게 단순 누락이 아니라 규제 공백인 이유: 이 Job의 대상(Reader의 EXISTS...
 * contract_status_event... 조건)이 바로 "실효·해지 등 상태변경이 있었던 계약"이고,
 * 차익거래 검증(FGC-FUN-063, docs/FGC_요구사항명세서_v2_2_2_...CSV:58)이 근거로 삼는
 * REG-12(docs/07_규제조문표_v0.2.1.md) 규제조문표는 "보장성보험 해지·실효 시 지급수수료+
 * 해약환급금을 누적보험료와 대사해 차익거래를 점검"하도록 요구한다 — 즉 이 Job이 매일
 * 잡아내는 계약들이 정확히 REG-12가 재검사를 요구하는 대상과 겹친다.
 *
 * 스케줄 재생성은 무조건 호출X - "예상 지급 스케줄은 계약·정책·상태 변경으로 재생성이 필요한 경우에만 영향 범위에서 호출합니다" (IF-BAT-02)
 */
@RequiredArgsConstructor
public class ChangedContractItemProcessor implements ItemProcessor<Long, ChangedContractResult> {

    private final ContractMapper contractMapper;
    private final ScheduleService scheduleService;
    private final CapCheckService capCheckService;
    private final ContractStatusEventProcessingMapper contractStatusEventProcessingMapper;

    private Long validationRunId;

    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        this.validationRunId = ValidationRunBatchContext.getValidationRunId(
                stepExecution.getJobExecution().getExecutionContext());
    }

    @Override
    public ChangedContractResult process(Long contractId) {
        List<Long> pendingEventIds = contractStatusEventProcessingMapper.findPendingEventIds(
                contractId, DailyChangedContractJobNames.JOB_NAME);

        try {
            InsuranceContract contract = contractMapper.selectById(contractId);
            if (contract == null) {
                // Reader가 목록을 뽑은 시점과 Processor가 실제로 조회하는 시점 사이에 계약이
                // 삭제될 일은 이 도메인에서는 없지만(계약은 논리 삭제/상태변경만 함), 방어적으로
                // 데이터 품질 스킵으로 처리한다.
                return ChangedContractResult.dataQualitySkip(
                        contractId, "계약을 찾을 수 없음: " + contractId, pendingEventIds);
            }

            if (!pendingEventIds.isEmpty()) {
                scheduleService.generateSchedules(contract);
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
