package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.service.CommissionPolicyService;
import com.susukkang.fgc.schedule.dto.ScheduleGenerationResult;
import com.susukkang.fgc.schedule.mapper.ScheduleMapper;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.dto.ValidationScheduleState;
import com.susukkang.fgc.validation.mapper.ExceptionCaseMapper;
import com.susukkang.fgc.validation.mapper.ValidationScheduleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ValidationRunScheduleServiceTest {

    @Mock ValidationScheduleMapper validationScheduleMapper;
    @Mock CommissionPolicyService commissionPolicyService;
    @Mock ScheduleMapper scheduleMapper;
    @Mock ExceptionCaseMapper exceptionCaseMapper;
    @Mock ScheduleRegenerationBatchItemService itemService;

    private ValidationRunScheduleService service;

    @BeforeEach
    void setUp() {
        service = new ValidationRunScheduleService(
                validationScheduleMapper,
                commissionPolicyService,
                scheduleMapper,
                exceptionCaseMapper,
                itemService);
    }

    @Test
    void delegatesValidContractToExistingScheduleService() {
        given(validationScheduleMapper.selectScheduleStates(118L))
                .willReturn(List.of(validState(10L, PaymentStage.INSURER_TO_GA),
                        validState(10L, PaymentStage.GA_TO_FC)));
        given(itemService.process(10L))
                .willReturn(new ScheduleGenerationResult(List.of(200L, 201L), 24));

        StepProcessingResult result = service.validateContractSchedules(118L);

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isZero();
        verify(itemService).process(10L);
        // FGC-FUN-043 — 새로 만든 헤더는 이 실행에 연결돼야 화면 집계에서 잡힌다.
        verify(scheduleMapper).linkHeadersToValidationRun(List.of(200L, 201L), 118L);
    }

    @Test
    void doesNotLinkHeadersWhenRegenerationCreatesNothingNew() {
        // 활성 스케줄이 이미 있어 generateSchedules()가 새 헤더를 안 만드는 경우 —
        // linkHeadersToValidationRun 호출 자체가 없어야 한다(빈 IN절 UPDATE 방지).
        given(validationScheduleMapper.selectScheduleStates(118L))
                .willReturn(List.of(validState(10L, PaymentStage.INSURER_TO_GA)));
        given(itemService.process(10L))
                .willReturn(new ScheduleGenerationResult(List.of(), 0));

        service.validateContractSchedules(118L);

        verify(scheduleMapper, never()).linkHeadersToValidationRun(any(), any());
    }

    @Test
    void skipsContractWithDuplicateActiveHeaderAndCreatesException() {
        ValidationScheduleState duplicate = validState(10L, PaymentStage.GA_TO_FC);
        duplicate.setActiveHeaderCount(2);
        given(validationScheduleMapper.selectScheduleStates(118L))
                .willReturn(List.of(duplicate));

        StepProcessingResult result = service.validateContractSchedules(118L);

        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.skips().getFirst().reasonCode()).isEqualTo("DATA_QUALITY");
        verify(exceptionCaseMapper).insertDataQualityCase(
                118L, 10L, "예상 스케줄 정합성 오류",
                "GA_TO_FC 활성 OPERATIONAL 스케줄 헤더가 중복되었습니다.");
        verify(itemService, never()).process(any());
    }

    @Test
    void skipsContractWhenApplicablePolicyIsMissing() {
        given(validationScheduleMapper.selectScheduleStates(118L))
                .willReturn(List.of(validState(10L, PaymentStage.GA_TO_FC)));
        given(commissionPolicyService.resolveCurrentCommission(
                10L, PaymentStage.INSURER_TO_GA))
                .willThrow(new FgcBusinessException(
                        FgcErrorCode.COMMON_002,
                        Map.of("reason", "POLICY_MISSING")));

        StepProcessingResult result = service.validateContractSchedules(118L);

        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.skips().getFirst().reasonCode()).isEqualTo("POLICY_MISSING");
        verify(scheduleMapper).upsertPolicyReviewCase(
                10L, PaymentStage.INSURER_TO_GA, "POLICY_MISSING",
                "예상 스케줄 생성 검토 필요", "적용 가능한 현행 수수료 정책이 없습니다.");
        verify(itemService, never()).process(any());
    }

    @Test
    void continuesWithNextContractWhenOneScheduleRegenerationFails() {
        given(validationScheduleMapper.selectScheduleStates(118L))
                .willReturn(List.of(
                        validState(10L, PaymentStage.INSURER_TO_GA),
                        validState(10L, PaymentStage.GA_TO_FC),
                        validState(20L, PaymentStage.INSURER_TO_GA),
                        validState(20L, PaymentStage.GA_TO_FC)));
        doThrow(new FgcBusinessException(FgcErrorCode.COMMON_002))
                .when(itemService).process(10L);
        given(itemService.process(20L))
                .willReturn(new ScheduleGenerationResult(List.of(202L), 12));

        StepProcessingResult result = service.validateContractSchedules(118L);

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.skips()).singleElement().satisfies(skip -> {
            assertThat(skip.contractId()).isEqualTo(10L);
            assertThat(skip.reasonCode()).isEqualTo("SCHEDULE_REGENERATION_FAILED");
        });
        verify(itemService).process(10L);
        verify(itemService).process(20L);
    }

    private ValidationScheduleState validState(Long contractId, PaymentStage paymentStage) {
        return ValidationScheduleState.builder()
                .contractId(contractId)
                .paymentStage(paymentStage)
                .scheduleHeaderId(20L)
                .policyVersionId(30L)
                .scheduleVersion(1)
                .scheduleStatus(ScheduleHeaderStatus.PLANNED)
                .activeHeaderCount(1)
                .lineCount(12)
                .distinctLineCount(12)
                .totalAmount(new BigDecimal("1200000"))
                .build();
    }
}
