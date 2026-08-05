package com.susukkang.fgc.cap.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapCheckDetailLine;
import com.susukkang.fgc.cap.dto.CapCheckInsertRow;
import com.susukkang.fgc.cap.dto.CapCheckListRow;
import com.susukkang.fgc.cap.dto.CapCheckRow;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.cap.dto.CapCheckSearchCriteria;
import com.susukkang.fgc.cap.dto.CapCheckSearchResult;
import com.susukkang.fgc.cap.dto.CapCheckStatusCount;
import com.susukkang.fgc.cap.mapper.CapCheckMapper;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CapCheckService(오케스트레이션 계층) 단위테스트.
 * CapCalculator/CapCheckMapper 를 mock 으로 대체해, "계산 결과를 어떻게 저장·조회로 조립하는지"만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class CapCheckServiceImplTest {

    @Mock
    private CapCalculator capCalculator;
    @Mock
    private CapCheckMapper capCheckMapper;

    private CapCheckServiceImpl capCheckService;

    private CapCalculationResult sampleResult(List<CapCheckDetailLine> details) {
        return new CapCalculationResult(
                1L, PaymentStage.GA_TO_FC, CapCheckKind.REALTIME, LocalDate.of(2026, 7, 10),
                500L, null,
                new BigDecimal("1200000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1200000"),
                new BigDecimal("650000"), new BigDecimal("550000"), new BigDecimal("54.166667"),
                CapResultStatus.NORMAL, details, Map.of("premiumMultiplier", "12.0000"));
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        capCheckService = new CapCheckServiceImpl(capCalculator, capCheckMapper, new ObjectMapper());
    }

    @Test
    void calculateAndSavePersistsCapCheckAndDetailsAndReturnsGeneratedId() {
        CapCheckDetailLine detail = new CapCheckDetailLine(
                1, 1L, "BASE_COMMISSION", "FC 기본수수료", 11L, 1, "INCLUDED", new BigDecimal("650000"), "산입", null);
        CapCalculationResult result = sampleResult(List.of(detail));

        CapCalculationCommand command = CapCalculationCommand.realtime(1L, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10));
        when(capCalculator.calculate(command)).thenReturn(result);

        // cap_check INSERT 는 생성된 PK 를 row 에 되채워 준다 — 실제 IDENTITY 컬럼 동작을 흉내낸다.
        doAnswer(invocation -> {
            CapCheckInsertRow row = invocation.getArgument(0);
            row.setCapCheckId(999L);
            return null;
        }).when(capCheckMapper).insertCapCheck(any());

        CapCheckSaveResult saved = capCheckService.calculateAndSave(command);

        assertThat(saved.capCheckId()).isEqualTo(999L);
        assertThat(saved.result()).isSameAs(result);

        ArgumentCaptor<CapCheckInsertRow> rowCaptor = ArgumentCaptor.forClass(CapCheckInsertRow.class);
        verify(capCheckMapper).insertCapCheck(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getContractId()).isEqualTo(1L);
        assertThat(rowCaptor.getValue().getLimitAmount()).isEqualByComparingTo("1200000");
        assertThat(rowCaptor.getValue().getResultStatus()).isEqualTo("NORMAL");

        verify(capCheckMapper).insertCapCheckDetails(argThatDetailListHasCapCheckId(999L));
    }

    @Test
    void calculateAndSaveSkipsDetailInsertWhenNoDetails() {
        CapCalculationResult result = sampleResult(List.of());
        CapCalculationCommand command = CapCalculationCommand.realtime(1L, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10));
        when(capCalculator.calculate(command)).thenReturn(result);

        capCheckService.calculateAndSave(command);

        verify(capCheckMapper, org.mockito.Mockito.never()).insertCapCheckDetails(any());
    }

    @Test
    void findLatestReturnsEmptyWhenNoCapCheckExists() {
        when(capCheckMapper.findLatestByContractAndStage(1L, "GA_TO_FC")).thenReturn(null);

        Optional<CapCheckSaveResult> found = capCheckService.findLatest(1L, PaymentStage.GA_TO_FC);

        assertThat(found).isEmpty();
    }

    @Test
    void findLatestReassemblesResultFromStoredRowAndDetails() {
        CapCheckRow row = new CapCheckRow();
        row.setCapCheckId(999L);
        row.setContractId(1L);
        row.setPaymentStage("GA_TO_FC");
        row.setCheckKind("REALTIME");
        row.setAsOfDate(LocalDate.of(2026, 7, 10));
        row.setCapRuleSetId(500L);
        row.setRefundRateTableId(null);
        row.setBasePremiumAmount(new BigDecimal("1200000"));
        row.setRefund12mAmount(BigDecimal.ZERO);
        row.setComplianceDeductionAmount(BigDecimal.ZERO);
        row.setLimitAmount(new BigDecimal("1200000"));
        row.setIncludedAmount(new BigDecimal("650000"));
        row.setRemainingAmount(new BigDecimal("550000"));
        row.setUsagePct(new BigDecimal("54.166667"));
        row.setResultStatus("NORMAL");
        row.setCalculationSnapshotJson("{\"premiumMultiplier\":\"12.0000\"}");

        List<CapCheckDetailLine> details = List.of(new CapCheckDetailLine(
                1, 1L, "BASE_COMMISSION", "FC 기본수수료", 11L, 1, "INCLUDED", new BigDecimal("650000"), "산입", null));

        when(capCheckMapper.findLatestByContractAndStage(1L, "GA_TO_FC")).thenReturn(row);
        when(capCheckMapper.findDetailsByCapCheckId(999L)).thenReturn(details);

        CapCheckSaveResult found = capCheckService.findLatest(1L, PaymentStage.GA_TO_FC).orElseThrow();

        assertThat(found.capCheckId()).isEqualTo(999L);
        assertThat(found.result().resultStatus()).isEqualTo(CapResultStatus.NORMAL);
        assertThat(found.result().limitAmount()).isEqualByComparingTo("1200000");
        assertThat(found.result().details()).hasSize(1);
        assertThat(found.result().calculationSnapshot()).containsEntry("premiumMultiplier", "12.0000");
    }

    @Test
    void findByContractReturnsBothPaymentStagesWithoutSumming() {
        CapCheckRow gaToFc = sampleRow(999L, "GA_TO_FC");
        CapCheckRow insurerToGa = sampleRow(1000L, "INSURER_TO_GA");
        when(capCheckMapper.findLatestPairByContract(1L)).thenReturn(List.of(gaToFc, insurerToGa));
        when(capCheckMapper.findDetailsByCapCheckId(999L)).thenReturn(List.of());
        when(capCheckMapper.findDetailsByCapCheckId(1000L)).thenReturn(List.of());

        List<CapCheckSaveResult> found = capCheckService.findByContract(1L);

        assertThat(found).hasSize(2);
        assertThat(found.get(0).result().paymentStage()).isEqualTo(PaymentStage.GA_TO_FC);
        assertThat(found.get(1).result().paymentStage()).isEqualTo(PaymentStage.INSURER_TO_GA);
    }

    @Test
    void findByIdReturnsEmptyWhenCapCheckDoesNotExist() {
        when(capCheckMapper.findById(1234L)).thenReturn(null);

        assertThat(capCheckService.findById(1234L)).isEmpty();
    }

    @Test
    void searchBuildsPageResponseAndSummaryFromMapperResults() {
        CapCheckListRow row = new CapCheckListRow();
        row.setCapCheckId(999L);
        row.setContractId(1L);
        row.setContractNo("C001");
        row.setPaymentStage("GA_TO_FC");
        row.setAsOfDate(LocalDate.of(2026, 7, 10));
        row.setBasePremiumAmount(new BigDecimal("1200000"));
        row.setRefund12mAmount(BigDecimal.ZERO);
        row.setComplianceDeductionAmount(BigDecimal.ZERO);
        row.setLimitAmount(new BigDecimal("1200000"));
        row.setIncludedAmount(new BigDecimal("650000"));
        row.setRemainingAmount(new BigDecimal("550000"));
        row.setUsagePct(new BigDecimal("54.166667"));
        row.setResultStatus("NORMAL");
        row.setCapRuleSetId(500L);

        CapCheckStatusCount normalCount = new CapCheckStatusCount();
        normalCount.setResultStatus("NORMAL");
        normalCount.setCount(3);

        CapCheckSearchCriteria criteria = new CapCheckSearchCriteria(
                LocalDate.of(2026, 7, 1), "GA_TO_FC", null, null, null);
        when(capCheckMapper.search(criteria.month(), criteria.paymentStage(), criteria.resultStatus(),
                criteria.insurerId(), criteria.contractNo(), 0, 20)).thenReturn(List.of(row));
        when(capCheckMapper.count(criteria.month(), criteria.paymentStage(), criteria.resultStatus(),
                criteria.insurerId(), criteria.contractNo())).thenReturn(1L);
        when(capCheckMapper.summarize(criteria.month(), criteria.paymentStage(),
                criteria.insurerId(), criteria.contractNo())).thenReturn(List.of(normalCount));

        CapCheckSearchResult result = capCheckService.search(criteria, 1, 20);

        assertThat(result.summary().normal()).isEqualTo(3);
        assertThat(result.page().content()).hasSize(1);
        assertThat(result.page().totalElements()).isEqualTo(1);
        assertThat(result.page().content().get(0).getContractNo()).isEqualTo("C001");
    }

    private static CapCheckRow sampleRow(Long capCheckId, String paymentStage) {
        CapCheckRow row = new CapCheckRow();
        row.setCapCheckId(capCheckId);
        row.setContractId(1L);
        row.setPaymentStage(paymentStage);
        row.setCheckKind("REALTIME");
        row.setAsOfDate(LocalDate.of(2026, 7, 10));
        row.setCapRuleSetId(500L);
        row.setBasePremiumAmount(new BigDecimal("1200000"));
        row.setRefund12mAmount(BigDecimal.ZERO);
        row.setComplianceDeductionAmount(BigDecimal.ZERO);
        row.setLimitAmount(new BigDecimal("1200000"));
        row.setIncludedAmount(new BigDecimal("650000"));
        row.setRemainingAmount(new BigDecimal("550000"));
        row.setUsagePct(new BigDecimal("54.166667"));
        row.setResultStatus("NORMAL");
        row.setCalculationSnapshotJson("{}");
        return row;
    }

    @SuppressWarnings("unchecked")
    private static List<com.susukkang.fgc.cap.dto.CapCheckDetailInsertRow> argThatDetailListHasCapCheckId(Long capCheckId) {
        return org.mockito.ArgumentMatchers.argThat(list ->
                list != null && list.stream().allMatch(d -> capCheckId.equals(d.getCapCheckId())));
    }
}
