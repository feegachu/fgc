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
import static org.mockito.ArgumentMatchers.eq;
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
    void delegatesValidContractToExistingScheduleServiceWithValidationRunId() {
        given(validationScheduleMapper.selectScheduleStates(118L))
                .willReturn(List.of(validState(10L, PaymentStage.INSURER_TO_GA),
                        validState(10L, PaymentStage.GA_TO_FC)));
        given(itemService.process(10L, 118L))
                .willReturn(new ScheduleGenerationResult(List.of(200L, 201L), 24));

        StepProcessingResult result = service.validateContractSchedules(118L);

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isZero();
        // FGC-FUN-043 결과 집계 — validationRunId를 itemService에 넘겨야 생성·연결이
        // 같은 REQUIRES_NEW 트랜잭션 안에서 처리된다(코드리뷰 반영). 이 Service 자신은
        // 더 이상 scheduleMapper.linkHeadersToValidationRun을 직접 호출하지 않는다 —
        // 그 책임은 ScheduleRegenerationBatchItemServiceTest가 검증한다.
        verify(itemService).process(10L, 118L);
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
        verify(itemService, never()).process(any(), any());
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
        verify(itemService, never()).process(any(), any());
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
                .when(itemService).process(10L, 118L);
        given(itemService.process(20L, 118L))
                .willReturn(new ScheduleGenerationResult(List.of(202L), 12));

        StepProcessingResult result = service.validateContractSchedules(118L);

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.skips()).singleElement().satisfies(skip -> {
            assertThat(skip.contractId()).isEqualTo(10L);
            assertThat(skip.reasonCode()).isEqualTo("SCHEDULE_REGENERATION_FAILED");
        });
        // 스케줄 생성 실패는 무음 스킵이 아니라 예외함에 떠야 한다.
        // 직급 공석 등으로 수취인을 찾지 못하면 계약 전체의 예상 스케줄이 0행이 되는데,
        // 케이스를 남기지 않으면 담당자가 그 사실을 알 수 없고 대사가 엉뚱한 판정을 낸다.
        verify(exceptionCaseMapper).insertDataQualityCase(
                eq(118L), eq(10L), eq("예상 스케줄 생성 실패"), any());
        verify(itemService).process(10L, 118L);
        verify(itemService).process(20L, 118L);
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
