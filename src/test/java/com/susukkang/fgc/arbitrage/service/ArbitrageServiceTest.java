package com.susukkang.fgc.arbitrage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.arbitrage.dto.ArbitrageCalculationSource;
import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.arbitrage.dto.ConfirmedCommissionSummary;
import com.susukkang.fgc.arbitrage.dto.ReArbitrageCheckRequest;
import com.susukkang.fgc.arbitrage.dto.ReArbitrageCheckResponse;
import com.susukkang.fgc.arbitrage.mapper.ArbitrageMapper;
import com.susukkang.fgc.common.code.ArbitrageCheckStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import com.susukkang.fgc.validation.mapper.ExceptionCaseMapper;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

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
    private ArbitrageMapper arbitrageMapper;
    @Mock
    private ValidationRunCreateService validationRunCreateService;
    @Mock
    private ValidationRunMapper validationRunMapper;
    @Mock
    private ExceptionCaseMapper exceptionCaseMapper;
    @Mock
    private AuditLogService auditLogService;

    private ArbitrageService arbitrageService;

    @BeforeEach
    void setUp() {
        arbitrageService = new ArbitrageService(
                arbitrageMapper,
                validationRunCreateService,
                validationRunMapper,
                exceptionCaseMapper,
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
        given(arbitrageMapper.selectCalculationSource(10L, asOfDate)).willReturn(source);
        given(arbitrageMapper.sumConfirmedCommissionAmount(
                10L, PaymentStage.GA_TO_FC, asOfDate)).willReturn(confirmed("900000", "0"));
        given(arbitrageMapper.sumPlannedCommissionAmount(
                10L, PaymentStage.GA_TO_FC)).willReturn(new BigDecimal("400000"));
        given(validationRunCreateService.create(any())).willReturn(validationRun(100L));
        given(validationRunMapper.transitionToRunning(100L)).willReturn(1);
        given(validationRunMapper.updateCurrentStep(100L, 5)).willReturn(1);
        given(arbitrageMapper.insertArbitrageCheck(any())).willAnswer(invocation -> {
            invocation.<com.susukkang.fgc.arbitrage.dto.ArbitrageCheckInsertDTO>getArgument(0)
                    .setArbitrageCheckId(200L);
            return 1;
        });
        given(validationRunMapper.transitionToCompleted(100L)).willReturn(1);

        ReArbitrageCheckResponse response = arbitrageService.reArbitrageCheck(
                10L,
                new ReArbitrageCheckRequest(asOfDate, "지급예정액 포함 재검증"),
                1L
        );

        assertThat(response.getArbitrageCheckId()).isEqualTo(200L);
        assertThat(response.getValidationRunId()).isEqualTo(100L);
        assertThat(response.getResultStatus()).isEqualTo(ArbitrageCheckStatus.CANDIDATE);
        verify(arbitrageMapper).insertArbitrageCheck(any());
        verify(exceptionCaseMapper).insertArbitrageCandidate(
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
        given(arbitrageMapper.selectCalculationSource(10L, asOfDate)).willReturn(source);
        given(arbitrageMapper.sumConfirmedCommissionAmount(
                10L, PaymentStage.GA_TO_FC, asOfDate)).willReturn(confirmed("900000", "0"));
        given(arbitrageMapper.sumPlannedCommissionAmount(
                10L, PaymentStage.GA_TO_FC)).willReturn(new BigDecimal("400000"));
        given(validationRunCreateService.create(any())).willReturn(validationRun(100L));
        given(validationRunMapper.transitionToRunning(100L)).willReturn(1);
        given(validationRunMapper.updateCurrentStep(100L, 5)).willReturn(1);
        given(arbitrageMapper.insertArbitrageCheck(any())).willAnswer(invocation -> {
            invocation.<com.susukkang.fgc.arbitrage.dto.ArbitrageCheckInsertDTO>getArgument(0)
                    .setArbitrageCheckId(200L);
            return 1;
        });
        given(validationRunMapper.transitionToCompleted(100L)).willReturn(1);

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
        given(arbitrageMapper.selectCalculationSource(10L, asOfDate)).willReturn(source);
        given(arbitrageMapper.sumConfirmedCommissionAmount(
                10L, PaymentStage.GA_TO_FC, asOfDate)).willReturn(confirmed("0", "0"));
        given(arbitrageMapper.sumPlannedCommissionAmount(
                10L, PaymentStage.GA_TO_FC)).willReturn(BigDecimal.ZERO);
        given(validationRunCreateService.create(any())).willReturn(validationRun(101L));
        given(validationRunMapper.transitionToRunning(101L)).willReturn(1);
        given(validationRunMapper.updateCurrentStep(101L, 5)).willReturn(1);
        given(arbitrageMapper.insertArbitrageCheck(any())).willAnswer(invocation -> {
            invocation.<com.susukkang.fgc.arbitrage.dto.ArbitrageCheckInsertDTO>getArgument(0)
                    .setArbitrageCheckId(201L);
            return 1;
        });
        given(validationRunMapper.transitionToCompleted(101L)).willReturn(1);

        ReArbitrageCheckResponse response = arbitrageService.reArbitrageCheck(
                10L,
                new ReArbitrageCheckRequest(asOfDate, "금융자료 확인"),
                1L
        );

        assertThat(response.getResultStatus()).isEqualTo(ArbitrageCheckStatus.REVIEW_REQUIRED);
        verify(exceptionCaseMapper).insertArbitrageReviewCase(
                "DATA_QUALITY", 101L, 10L, 201L, "GA_TO_FC",
                "차익거래 검증 자료 확인 필요",
                "기준일 이하 계약 금융 스냅샷이 없습니다.");
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
