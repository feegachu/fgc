package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.arbitrage.service.ArbitrageCheckBatchItemService;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.contract.entity.ContractStatusEvent;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.contract.repository.ContractStatusEventRepository;
import com.susukkang.fgc.contract.repository.InsuranceContractRepository;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.validation.repository.ContractStatusEventProcessingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChangedContractItemProcessorTest {

    @Mock
    private InsuranceContractRepository insuranceContractRepository;
    @Mock
    private ScheduleService scheduleService;
    @Mock
    private CapCheckService capCheckService;
    @Mock
    private ContractStatusEventProcessingRepository contractStatusEventProcessingRepository;
    @Mock
    private ArbitrageCheckBatchItemService arbitrageCheckBatchItemService;
    @Mock
    private ContractStatusEventRepository contractStatusEventRepository;

    private ChangedContractItemProcessor processor;
    private final OffsetDateTime watermark = OffsetDateTime.parse("2026-08-10T02:00:00+09:00");

    @BeforeEach
    void setUp() {
        processor = new ChangedContractItemProcessor(
                insuranceContractRepository, scheduleService, capCheckService, contractStatusEventProcessingRepository,
                arbitrageCheckBatchItemService, contractStatusEventRepository);

        JobExecution jobExecution = new JobExecution(
                new JobInstance(1L, DailyChangedContractJobNames.JOB_NAME), new JobParameters());
        jobExecution.getExecutionContext().putLong("validationRunId", 777L);
        jobExecution.getExecutionContext().putString("lastProcessedAt", watermark.toString());
        processor.beforeStep(new StepExecution("changedContractStep", jobExecution));
    }

    // 기본 계약은 watermark보다 이전에 바뀐 것으로 만든다 — 그래야 순수 상태 이벤트 유무만으로
    // 재생성 여부가 갈리는 기존 테스트들의 전제가 그대로 유지된다.
    private InsuranceContract contract(long contractId) {
        InsuranceContract contract = InsuranceContract.builder().build();
        ReflectionTestUtils.setField(contract, "contractId", contractId);
        ReflectionTestUtils.setField(contract, "updatedAt", watermark.minusDays(1));
        return contract;
    }

    @Test
    void pendingStatusEventTriggersScheduleRegenerationAndChecksBothPaymentStages() {
        given(insuranceContractRepository.findById(1L)).willReturn(Optional.of(contract(1L)));
        given(contractStatusEventProcessingRepository.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of(100L));

        ChangedContractResult result = processor.process(1L);

        assertThat(result.success()).isTrue();
        assertThat(result.contractId()).isEqualTo(1L);
        assertThat(result.pendingEventIds()).containsExactly(100L);
        verify(scheduleService).generateSchedulesInNewTransaction(any());

        // cap_check가 이 배치의 validation_run에 실제로 연결되는지 확인한다(코드리뷰 지적,
        // 2026-08-11) — CapCalculationCommand.realtime()을 그대로 썼다면 validationRunId가
        // 항상 null이라 uq_cap_check_monthly 중복방지가 무력화되고 §7-5 "그 밑에 결과를
        // 붙인다"도 어긋난다.
        ArgumentCaptor<CapCalculationCommand> captor = ArgumentCaptor.forClass(CapCalculationCommand.class);
        // PaymentStage 2개(INSURER_TO_GA, GA_TO_FC) 각각 1번씩 -> 총 2번
        verify(capCheckService, times(2)).calculateAndSave(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(command -> assertThat(command.validationRunId()).isEqualTo(777L));
        // #512 — 차익거래도 같은 validation_run 밑에, 해지·실효가 아닌 계약은 오늘 기준으로 점검한다.
        verify(arbitrageCheckBatchItemService).process(777L, 1L, LocalDate.now(DateUtil.SEOUL_ZONE));
    }

    @Test
    void noPendingStatusEventSkipsScheduleRegenerationButStillChecksCap() {
        given(insuranceContractRepository.findById(1L)).willReturn(Optional.of(contract(1L)));
        given(contractStatusEventProcessingRepository.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of());

        ChangedContractResult result = processor.process(1L);

        assertThat(result.success()).isTrue();
        verify(scheduleService, never()).generateSchedulesInNewTransaction(any());
        verify(capCheckService, times(2)).calculateAndSave(any(CapCalculationCommand.class));
    }

    // FGC-FUN-039의 "언제" 트리거는 상태 이벤트뿐 아니라 "계약·정책 변경"도 포함한다.
    // ContractService#updateContract가 보험료·납입기간 등을 바꿔도 contract_status_event를
    // 안 남기는 경로가 있어서, 상태 이벤트 유무만으로 재생성을 판단하면 이 경로가 조용히
    // 누락된다(코드리뷰 지적, 2026-08-11) — updated_at이 watermark 이후인지도 같이 본다.
    @Test
    void contractUpdatedAfterWatermarkTriggersScheduleRegenerationEvenWithoutStatusEvent() {
        InsuranceContract contract = contract(1L);
        ReflectionTestUtils.setField(contract, "updatedAt", watermark.plusHours(1));
        given(insuranceContractRepository.findById(1L)).willReturn(Optional.of(contract));
        given(contractStatusEventProcessingRepository.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of());

        ChangedContractResult result = processor.process(1L);

        assertThat(result.success()).isTrue();
        verify(scheduleService).generateSchedulesInNewTransaction(any());
    }

    @Test
    void missingContractIsTreatedAsDataQualitySkip() {
        given(insuranceContractRepository.findById(2L)).willReturn(Optional.empty());

        ChangedContractResult result = processor.process(2L);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).contains("2");
    }

    @Test
    void businessExceptionDuringScheduleGenerationIsTreatedAsDataQualitySkipNotStepFailure() {
        given(insuranceContractRepository.findById(3L)).willReturn(Optional.of(contract(3L)));
        given(contractStatusEventProcessingRepository.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of(100L));
        willThrow(new FgcBusinessException(FgcErrorCode.CONT_001, Map.of()))
                .given(scheduleService).generateSchedulesInNewTransaction(any());

        ChangedContractResult result = processor.process(3L);

        assertThat(result.success()).isFalse();
        assertThat(result.contractId()).isEqualTo(3L);
        assertThat(result.failureReason()).contains(FgcErrorCode.CONT_001.getCode());
    }

    // #512 — 해지·실효 계약의 차익거래 기준일은 효력일(Asia/Seoul 날짜)이다(제4-32조⑥ "해지시점까지", FAQ 2차 Q12).
    // effective_at 2026-08-20 00:00 KST는 UTC로 8/19 15:00이라, 서울 시간대로 바꾸지 않으면 하루 앞당겨진다.
    @Test
    void terminatedContractIsCheckedForArbitrageAsOfEffectiveDateInSeoul() {
        InsuranceContract contract = contract(4L);
        ReflectionTestUtils.setField(contract, "currentStatus", ContractStatus.TERMINATED);
        given(insuranceContractRepository.findById(4L)).willReturn(Optional.of(contract));
        given(contractStatusEventProcessingRepository.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of(200L));
        given(contractStatusEventRepository.findFirstByContractIdOrderByEffectiveAtDescEventSeqDesc(4L))
                .willReturn(Optional.of(event(ContractStatus.TERMINATED, OffsetDateTime.parse("2026-08-19T15:00:00Z"))));

        ChangedContractResult result = processor.process(4L);

        assertThat(result.success()).isTrue();
        verify(arbitrageCheckBatchItemService).process(777L, 4L, LocalDate.of(2026, 8, 20));
    }

    // 사건 없이 상태만 바뀐 계약(최근 사건 ACTIVE, 현재 LAPSED)은 효력일을 알 수 없어 오늘을 쓴다.
    @Test
    void lapsedContractWhoseLatestEventDisagreesFallsBackToToday() {
        InsuranceContract contract = contract(5L);
        ReflectionTestUtils.setField(contract, "currentStatus", ContractStatus.LAPSED);
        given(insuranceContractRepository.findById(5L)).willReturn(Optional.of(contract));
        given(contractStatusEventProcessingRepository.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of());
        given(contractStatusEventRepository.findFirstByContractIdOrderByEffectiveAtDescEventSeqDesc(5L))
                .willReturn(Optional.of(event(ContractStatus.ACTIVE, OffsetDateTime.parse("2026-01-15T00:00:00+09:00"))));

        processor.process(5L);

        verify(arbitrageCheckBatchItemService).process(777L, 5L, LocalDate.now(DateUtil.SEOUL_ZONE));
    }

    // #512 — 차익거래만 실패해도 SUCCEEDED로 찍히면 그 해지 사건은 다시 점검되지 않는다.
    // 데이터 품질 스킵으로 돌려 Writer가 사건을 FAILED로 남기게 한다(다음 날 Reader가 다시 잡는다).
    @Test
    void arbitrageFailureIsDataQualitySkipSoPendingEventsStayRetryable() {
        given(insuranceContractRepository.findById(6L)).willReturn(Optional.of(contract(6L)));
        given(contractStatusEventProcessingRepository.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of(300L));
        willThrow(new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", 6L)))
                .given(arbitrageCheckBatchItemService).process(eq(777L), eq(6L), any(LocalDate.class));

        ChangedContractResult result = processor.process(6L);

        assertThat(result.success()).isFalse();
        assertThat(result.pendingEventIds()).containsExactly(300L);
        assertThat(result.failureReason()).contains(FgcErrorCode.COMMON_004.getCode());
    }

    // #512 — 1,200% 점검이 실패해도 차익거래는 점검한다.
    // 월 실행처럼 두 검증이 서로를 막지 않되, 실패가 있으면 사건은 FAILED로 남도록 스킵을 돌려준다.
    // (시행 전 계약의 CAP_004는 실패가 아니게 바뀌어(#522) 다른 업무 예외로 의도를 지킨다.)
    @Test
    void capFailureStillRunsArbitrageAndReturnsSkip() {
        given(insuranceContractRepository.findById(7L)).willReturn(Optional.of(contract(7L)));
        given(contractStatusEventProcessingRepository.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of(400L));
        willThrow(new FgcBusinessException(FgcErrorCode.CAP_002, Map.of()))
                .given(capCheckService).calculateAndSave(any(CapCalculationCommand.class));

        ChangedContractResult result = processor.process(7L);

        verify(arbitrageCheckBatchItemService).process(777L, 7L, LocalDate.now(DateUtil.SEOUL_ZONE));
        assertThat(result.success()).isFalse();
        assertThat(result.pendingEventIds()).containsExactly(400L);
        assertThat(result.failureReason()).contains(FgcErrorCode.CAP_002.getCode());
        verify(scheduleService, never()).registerCapRuleReview(anyLong(), any(), anyString());
    }

    // #522 — 1,200%룰은 보험회사 '21.1.1, 보험대리점 '26.7.1. "이후 체결한 신계약"에 적용된다(FAQ 2차 Q1-1).
    // 시행 전 체결 계약의 룰셋 없음(CAP_004)은 정상이라 실패도 검토 대상도 아니다. 앞 단계가 CAP_004여도 뒤 단계는 계산한다.
    @Test
    void missingRuleSetForContractBeforeCapRuleEffectiveDateIsNeitherFailureNorReview() {
        given(insuranceContractRepository.findById(8L)).willReturn(Optional.of(contract(8L, LocalDate.of(2020, 12, 31))));
        given(contractStatusEventProcessingRepository.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of(500L));
        failCapStages(Map.of(
                PaymentStage.INSURER_TO_GA, missingRuleSet(),
                PaymentStage.GA_TO_FC, missingRuleSet()));

        ChangedContractResult result = processor.process(8L);

        assertThat(result.success()).isTrue();
        assertThat(result.pendingEventIds()).containsExactly(500L);
        verify(capCheckService, times(2)).calculateAndSave(any(CapCalculationCommand.class));
        verify(scheduleService, never()).registerCapRuleReview(anyLong(), any(), anyString());
        verify(arbitrageCheckBatchItemService).process(777L, 8L, LocalDate.now(DateUtil.SEOUL_ZONE));
    }

    // #522 — 대상 단계(시행일 당일 체결 = "이후"에 포함)인데 룰셋이 없으면 설정 누락이다.
    // 검토 큐에 남기고 실패로 돌려 사건이 FAILED로 남게 한다(다음 날 다시 점검).
    @Test
    void missingRuleSetForApplicableStageIsReviewedAndStaysRetryable() {
        given(insuranceContractRepository.findById(9L)).willReturn(Optional.of(contract(9L, LocalDate.of(2026, 7, 1))));
        given(contractStatusEventProcessingRepository.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of(600L));
        failCapStages(Map.of(PaymentStage.GA_TO_FC, missingRuleSet()));

        ChangedContractResult result = processor.process(9L);

        assertThat(result.success()).isFalse();
        assertThat(result.pendingEventIds()).containsExactly(600L);
        assertThat(result.failureReason()).isEqualTo(FgcErrorCode.CAP_004.getCode() + ": 적용 가능한 1,200% 룰셋이 없습니다.");
        verify(scheduleService).registerCapRuleReview(9L, PaymentStage.GA_TO_FC, "적용 가능한 1,200% 룰셋이 없습니다.");
        verify(scheduleService, never()).registerCapRuleReview(eq(9L), eq(PaymentStage.INSURER_TO_GA), anyString());
    }

    // #522 — 시행 전 단계의 CAP_004는 빠지고, 다른 업무 예외만 실패 사유로 남는다(재시도는 유지).
    @Test
    void otherCapFailureAlongsidePreEffectiveMissingRuleSetStillSkips() {
        given(insuranceContractRepository.findById(10L)).willReturn(Optional.of(contract(10L, LocalDate.of(2026, 6, 30))));
        given(contractStatusEventProcessingRepository.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of(700L));
        failCapStages(Map.of(
                PaymentStage.INSURER_TO_GA, new FgcBusinessException(FgcErrorCode.CAP_002, Map.of()),
                PaymentStage.GA_TO_FC, missingRuleSet()));

        ChangedContractResult result = processor.process(10L);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo(FgcErrorCode.CAP_002.getCode());
        verify(scheduleService, never()).registerCapRuleReview(anyLong(), any(), anyString());
    }

    private InsuranceContract contract(long contractId, LocalDate contractDate) {
        InsuranceContract contract = contract(contractId);
        ReflectionTestUtils.setField(contract, "contractDate", contractDate);
        return contract;
    }

    private FgcBusinessException missingRuleSet() {
        return new FgcBusinessException(FgcErrorCode.CAP_004, "paymentStage", Map.of(), "적용 가능한 1,200% 룰셋이 없습니다.");
    }

    private void failCapStages(Map<PaymentStage, FgcBusinessException> failures) {
        willAnswer(invocation -> {
            FgcBusinessException failure = failures.get(invocation.<CapCalculationCommand>getArgument(0).paymentStage());
            if (failure != null) {
                throw failure;
            }
            return null;
        }).given(capCheckService).calculateAndSave(any(CapCalculationCommand.class));
    }

    private ContractStatusEvent event(ContractStatus newStatus, OffsetDateTime effectiveAt) {
        return ContractStatusEvent.builder().newStatus(newStatus).effectiveAt(effectiveAt).build();
    }
}
