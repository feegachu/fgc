package com.susukkang.fgc.arbitrage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.arbitrage.dto.ArbitrageCalculationSource;
import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckSearchCondition;
import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckSummary;
import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.arbitrage.dto.ConfirmedCommissionSummary;
import com.susukkang.fgc.arbitrage.dto.ReArbitrageCheckRequest;
import com.susukkang.fgc.arbitrage.dto.ReArbitrageCheckResponse;
import com.susukkang.fgc.arbitrage.repository.ArbitrageCheckRepository;
import com.susukkang.fgc.common.code.ArbitrageCheckStatus;
import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseRepository;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 설명 : 계약 단건 차익거래 수동 검증의 계산과 결과 저장을 검증한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@ExtendWith(MockitoExtension.class)
class ArbitrageServiceTest {
    @Mock
    private ArbitrageCheckRepository arbitrageCheckRepository;
    @Mock
    private ValidationRunCreateService validationRunCreateService;
    @Mock
    private ValidationRunRepository validationRunRepository;
    @Mock
    private ExceptionCaseRepository exceptionCaseRepository;
    @Mock
    private AuditLogService auditLogService;

    private ArbitrageService arbitrageService;

    @BeforeEach
    void setUp() {
        arbitrageService = new ArbitrageService(
                arbitrageCheckRepository,
                validationRunCreateService,
                validationRunRepository,
                exceptionCaseRepository,
                new ObjectMapper().findAndRegisterModules(),
                auditLogService
        );
    }

    /**
     * 설명 : 현재는 미초과지만 남은 지급예정액을 포함하면 후보로 판정하는지 검증한다.
     *
     * @author hjKang
     * @since 2026-08-12
     */
    @Test
    void marksProjectedExcessAsCandidate() {
        LocalDate asOfDate = LocalDate.of(2026, 7, 31);
        ArbitrageCalculationSource source = calculationSource(
                new BigDecimal("1200000"), 12, false, BigDecimal.ZERO);
        given(arbitrageCheckRepository.selectCalculationSource(10L, asOfDate)).willReturn(source);
        given(arbitrageCheckRepository.sumConfirmedCommissionAmount(
                10L, PaymentStage.GA_TO_FC, asOfDate)).willReturn(confirmed("900000", "0"));
        given(arbitrageCheckRepository.sumPlannedCommissionAmount(
                10L, PaymentStage.GA_TO_FC)).willReturn(new BigDecimal("400000"));
        given(validationRunCreateService.create(any())).willReturn(validationRun(100L));
        given(validationRunRepository.transitionToRunning(100L, ValidationRunStatus.CREATED, ValidationRunStatus.RUNNING)).willReturn(1);
        given(validationRunRepository.updateCurrentStep(100L, 5, ValidationRunStatus.RUNNING)).willReturn(1);
        given(arbitrageCheckRepository.insertArbitrageCheck(any())).willAnswer(invocation -> {
            invocation.<com.susukkang.fgc.arbitrage.dto.ArbitrageCheckInsertDTO>getArgument(0)
                    .setArbitrageCheckId(200L);
            return 1;
        });
        given(validationRunRepository.transitionToCompleted(100L, ValidationRunStatus.RUNNING, ValidationRunStatus.COMPLETED)).willReturn(1);

        ReArbitrageCheckResponse response = arbitrageService.reArbitrageCheck(
                10L,
                new ReArbitrageCheckRequest(asOfDate, "지급예정액 포함 재검증"),
                1L
        );

        assertThat(response.getArbitrageCheckId()).isEqualTo(200L);
        assertThat(response.getValidationRunId()).isEqualTo(100L);
        assertThat(response.getResultStatus()).isEqualTo(ArbitrageCheckStatus.CANDIDATE);
        verify(arbitrageCheckRepository).insertArbitrageCheck(any());
        verify(exceptionCaseRepository).insertArbitrageCandidate(
                100L, 10L, 200L, "GA_TO_FC",
                "지급예정 수수료를 포함하면 누적 납입보험료를 초과합니다.");
    }

    /**
     * FUN-061·운영정책서 제51조 — 수동 재검증은 실행 사용자·사유와 함께 감사행을 남긴다.
     * 실DB 통합 검증은 두지 않는다: 실행 생성이 REQUIRES_NEW 로 별도 커밋되어(ValidationRunCreateServiceImpl)
     * 테스트 롤백으로 정리되지 않고 uq_validation_run_active_manual_contract 잔존 충돌을 일으킨다.
     * 감사행의 DB 왕복은 AuditLogQueryMapperIntegrationTest 가 검증한다.
     */
    @Test
    void recordsArbitrageRecheckedAudit() {
        LocalDate asOfDate = LocalDate.of(2026, 7, 31);
        ArbitrageCalculationSource source = calculationSource(
                new BigDecimal("1200000"), 12, false, BigDecimal.ZERO);
        given(arbitrageCheckRepository.selectCalculationSource(10L, asOfDate)).willReturn(source);
        given(arbitrageCheckRepository.sumConfirmedCommissionAmount(
                10L, PaymentStage.GA_TO_FC, asOfDate)).willReturn(confirmed("900000", "0"));
        given(arbitrageCheckRepository.sumPlannedCommissionAmount(
                10L, PaymentStage.GA_TO_FC)).willReturn(new BigDecimal("400000"));
        given(validationRunCreateService.create(any())).willReturn(validationRun(100L));
        given(validationRunRepository.transitionToRunning(100L, ValidationRunStatus.CREATED, ValidationRunStatus.RUNNING)).willReturn(1);
        given(validationRunRepository.updateCurrentStep(100L, 5, ValidationRunStatus.RUNNING)).willReturn(1);
        given(arbitrageCheckRepository.insertArbitrageCheck(any())).willAnswer(invocation -> {
            invocation.<com.susukkang.fgc.arbitrage.dto.ArbitrageCheckInsertDTO>getArgument(0)
                    .setArbitrageCheckId(200L);
            return 1;
        });
        given(validationRunRepository.transitionToCompleted(100L, ValidationRunStatus.RUNNING, ValidationRunStatus.COMPLETED)).willReturn(1);

        arbitrageService.reArbitrageCheck(
                10L,
                new ReArbitrageCheckRequest(asOfDate, "지급예정액 포함 재검증"),
                1L
        );

        org.mockito.ArgumentCaptor<AuditLogService.AuditEvent> captor =
                org.mockito.ArgumentCaptor.forClass(AuditLogService.AuditEvent.class);
        verify(auditLogService).record(captor.capture());
        AuditLogService.AuditEvent event = captor.getValue();
        assertThat(event.actionCode()).isEqualTo("ARBITRAGE_RECHECKED");
        assertThat(event.entityType()).isEqualTo("ARBITRAGE_CHECK");
        assertThat(event.entityId()).isEqualTo("200");
        assertThat(event.userId()).isEqualTo(1L);
        assertThat(event.reason()).isEqualTo("지급예정액 포함 재검증");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> after = (java.util.Map<String, Object>) event.after();
        assertThat(after).containsKeys("validationRunId", "contractId", "resultStatus");
    }

    @Test
    void keepsSummaryCountsAcrossStatusCardFiltering() {
        ArbitrageCheckSearchCondition condition = new ArbitrageCheckSearchCondition(
                YearMonth.of(2026, 8), ArbitrageCheckStatus.CANDIDATE,
                PaymentStage.GA_TO_FC, 10L, "C001");
        ArbitrageCheckSummary summary = new ArbitrageCheckSummary();
        summary.setClearCount(3);
        summary.setCandidateCount(2);
        summary.setReviewRequiredCount(1);
        given(arbitrageCheckRepository.selectByCondition(condition, 0, 20)).willReturn(List.of());
        given(arbitrageCheckRepository.arbitrageCheckSummary(any())).willReturn(summary);
        given(arbitrageCheckRepository.countByCondition(condition)).willReturn(2L);

        var response = arbitrageService.selectByCondition(condition, 1, 20);

        assertThat(response.getSummary().getTotalArbitrageChecks()).isEqualTo(6);
        assertThat(response.getItems().totalElements()).isEqualTo(2);
        org.mockito.ArgumentCaptor<ArbitrageCheckSearchCondition> summaryCondition =
                org.mockito.ArgumentCaptor.forClass(ArbitrageCheckSearchCondition.class);
        verify(arbitrageCheckRepository).arbitrageCheckSummary(summaryCondition.capture());
        assertThat(summaryCondition.getValue().getStatus()).isNull();
        assertThat(summaryCondition.getValue().getMonth()).isEqualTo(condition.getMonth());
    }

    /**
     * 설명 : 기준일 이하 금융 스냅샷이 없으면 확정 판정 대신 자료검토로 저장하는지 검증한다.
     *
     * @author hjKang
     * @since 2026-08-12
     */
    @Test
    void marksMissingFinancialSnapshotAsReviewRequired() {
        LocalDate asOfDate = LocalDate.of(2026, 7, 31);
        ArbitrageCalculationSource source = calculationSource(null, null, false, null);
        given(arbitrageCheckRepository.selectCalculationSource(10L, asOfDate)).willReturn(source);
        given(arbitrageCheckRepository.sumConfirmedCommissionAmount(
                10L, PaymentStage.GA_TO_FC, asOfDate)).willReturn(confirmed("0", "0"));
        given(arbitrageCheckRepository.sumPlannedCommissionAmount(
                10L, PaymentStage.GA_TO_FC)).willReturn(BigDecimal.ZERO);
        given(validationRunCreateService.create(any())).willReturn(validationRun(101L));
        given(validationRunRepository.transitionToRunning(101L, ValidationRunStatus.CREATED, ValidationRunStatus.RUNNING)).willReturn(1);
        given(validationRunRepository.updateCurrentStep(101L, 5, ValidationRunStatus.RUNNING)).willReturn(1);
        given(arbitrageCheckRepository.insertArbitrageCheck(any())).willAnswer(invocation -> {
            invocation.<com.susukkang.fgc.arbitrage.dto.ArbitrageCheckInsertDTO>getArgument(0)
                    .setArbitrageCheckId(201L);
            return 1;
        });
        given(validationRunRepository.transitionToCompleted(101L, ValidationRunStatus.RUNNING, ValidationRunStatus.COMPLETED)).willReturn(1);

        ReArbitrageCheckResponse response = arbitrageService.reArbitrageCheck(
                10L,
                new ReArbitrageCheckRequest(asOfDate, "금융자료 확인"),
                1L
        );

        assertThat(response.getResultStatus()).isEqualTo(ArbitrageCheckStatus.REVIEW_REQUIRED);
        verify(exceptionCaseRepository).insertArbitrageReviewCase(
                "DATA_QUALITY", 101L, 10L, 201L, "GA_TO_FC",
                "차익거래 검증 자료 확인 필요",
                "기준일 이하 계약 금융 스냅샷이 없습니다.");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"36, 100000, CANDIDATE", "37, 0, CLEAR"})
    void preservesRefundAdditionBoundary(int month, String refundAmount, ArbitrageCheckStatus status) {
        var source = calculationSource(new BigDecimal("1000000"), month, true, new BigDecimal("100000.49"));
        var row = checkExisting(source, "1000000", "0");
        assertThat(row.getIncludedSurrenderValueAmount()).isEqualByComparingTo(refundAmount);
        assertThat(row.getResultStatus()).isEqualTo(status);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"999999.49, CLEAR", "1000000.49, CLEAR", "1000000.50, CANDIDATE"})
    void preservesWonRoundingAndStrictPositiveCandidateBoundary(String paid, ArbitrageCheckStatus expected) {
        var row = checkExisting(calculationSource(new BigDecimal("1000000"), 12, false, BigDecimal.ZERO), paid, "0");
        assertThat(row.getResultStatus()).isEqualTo(expected);
        assertThat(row.getNetDifferenceAmount()).isEqualByComparingTo(expected == ArbitrageCheckStatus.CLEAR ? "0" : "1");
    }

    @Test
    void retainsExpectedRefundSourceAndRejectsMismatchedTable() {
        var source = calculationSource(new BigDecimal("1000000"), 12, true, new BigDecimal("100000"));
        source.setSurrenderValueType("EXPECTED_TABLE");
        source.setSnapshotRefundRateTableId(11L);
        var candidate = new com.susukkang.fgc.arbitrage.dto.ArbitrageRefundRateCandidate();
        candidate.setRefundRateTableId(12L);
        given(arbitrageCheckRepository.selectRefundRateCandidates(source, source.getContractDate())).willReturn(List.of(candidate));
        var row = checkExisting(source, "0", "0");
        assertThat(row.getResultStatus()).isEqualTo(ArbitrageCheckStatus.REVIEW_REQUIRED);
        verify(exceptionCaseRepository).insertArbitrageReviewCase("PRODUCT_CODE_MISMATCH", 100L, 10L, 200L,
                "GA_TO_FC", "차익거래 검증 자료 확인 필요", "금융 스냅샷과 적용 환급률표가 일치하지 않습니다.");
    }

    private com.susukkang.fgc.arbitrage.dto.ArbitrageCheckInsertDTO checkExisting(ArbitrageCalculationSource source, String paid, String planned) {
        LocalDate date = LocalDate.of(2026, 7, 31);
        given(arbitrageCheckRepository.selectCalculationSource(10L, date)).willReturn(source);
        given(arbitrageCheckRepository.sumConfirmedCommissionAmount(10L, PaymentStage.GA_TO_FC, date)).willReturn(confirmed(paid, "0"));
        given(arbitrageCheckRepository.sumPlannedCommissionAmount(10L, PaymentStage.GA_TO_FC)).willReturn(new BigDecimal(planned));
        given(arbitrageCheckRepository.insertArbitrageCheck(any())).willAnswer(invocation -> {
            invocation.<com.susukkang.fgc.arbitrage.dto.ArbitrageCheckInsertDTO>getArgument(0).setArbitrageCheckId(200L);
            return 1;
        });
        return arbitrageService.checkInExistingRun(100L, 10L, date);
    }

    private ArbitrageCalculationSource calculationSource(
            BigDecimal cumulativePaidPremium,
            Integer contractMonthNo,
            boolean standardDeduction80Yn,
            BigDecimal surrenderValue) {
        ArbitrageCalculationSource source = new ArbitrageCalculationSource();
        source.setContractId(10L);
        source.setContractDate(LocalDate.of(2026, 1, 1));
        source.setInsurerId(1L);
        source.setProductId(1L);
        source.setProductOfferingId(1L);
        source.setPaymentTermMonths(120);
        source.setChannelCode("GA");
        source.setStandardDeduction80Yn(standardDeduction80Yn);
        source.setSnapshotAsOfDate(contractMonthNo == null ? null : LocalDate.of(2026, 7, 31));
        source.setContractMonthNo(contractMonthNo);
        source.setCumulativePaidPremium(cumulativePaidPremium);
        source.setSurrenderValue(surrenderValue);
        source.setSurrenderValueType("ACTUAL");
        return source;
    }

    private ValidationRunRow validationRun(Long validationRunId) {
        ValidationRunRow row = new ValidationRunRow();
        row.setValidationRunId(validationRunId);
        return row;
    }

    private ConfirmedCommissionSummary confirmed(String payment, String deduction) {
        ConfirmedCommissionSummary summary = new ConfirmedCommissionSummary();
        summary.setConfirmedPaymentAmount(new BigDecimal(payment));
        summary.setConfirmedDeductionAmount(new BigDecimal(deduction));
        summary.setPaidCommissionAmount(new BigDecimal(payment).subtract(new BigDecimal(deduction)));
        return summary;
    }
}
