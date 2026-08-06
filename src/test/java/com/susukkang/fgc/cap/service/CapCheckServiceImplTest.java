package com.susukkang.fgc.cap.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapCheckBasisResponse;
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
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
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

    // item_code/item_name/contract_month_no는 계산 당시 값을 cap_check_detail에 그대로 스냅샷해야
    // 한다 — commission_item/schedule_line이 나중에 바뀌어도 과거 판정 근거가 그때 값으로 남게 하려면
    // INSERT 시점에 빠짐없이 넘어가야 한다 (PR #2 코드리뷰 지적)
    @Test
    void calculateAndSaveSnapshotsItemCodeNameAndContractMonthNoOnDetailRows() {
        CapCheckDetailLine detail = new CapCheckDetailLine(
                1, 1L, "BASE_COMMISSION", "FC 기본수수료", 11L, 3, "INCLUDED", new BigDecimal("650000"), "산입", null);
        CapCalculationResult result = sampleResult(List.of(detail));

        CapCalculationCommand command = CapCalculationCommand.realtime(1L, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10));
        when(capCalculator.calculate(command)).thenReturn(result);
        doAnswer(invocation -> {
            CapCheckInsertRow row = invocation.getArgument(0);
            row.setCapCheckId(999L);
            return null;
        }).when(capCheckMapper).insertCapCheck(any());

        capCheckService.calculateAndSave(command);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.susukkang.fgc.cap.dto.CapCheckDetailInsertRow>> detailsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(capCheckMapper).insertCapCheckDetails(detailsCaptor.capture());

        com.susukkang.fgc.cap.dto.CapCheckDetailInsertRow insertedDetail = detailsCaptor.getValue().get(0);
        assertThat(insertedDetail.getItemCode()).isEqualTo("BASE_COMMISSION");
        assertThat(insertedDetail.getItemName()).isEqualTo("FC 기본수수료");
        assertThat(insertedDetail.getContractMonthNo()).isEqualTo(3);
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
    void findDetailReturnsEmptyWhenCapCheckIdDoesNotExist() {
        when(capCheckMapper.findById(999L)).thenReturn(null);

        Optional<CapCheckBasisResponse> found = capCheckService.findDetail(999L);

        assertThat(found).isEmpty();
    }

    // IF-API-31(api-spec.md): capCheck + details[] + calculationSnapshot 구조로 조립해야 한다.
    @Test
    void findDetailAssemblesBasisResponseWithNestedCapCheckAndDetails() {
        CapCheckRow row = new CapCheckRow();
        row.setCapCheckId(999L);
        row.setContractId(1L);
        row.setContractNo("C001");
        row.setPaymentStage("GA_TO_FC");
        row.setCheckKind("REALTIME");
        row.setAsOfDate(LocalDate.of(2026, 7, 10));
        row.setCapRuleSetId(500L);
        row.setRefundRateTableId(null);
        // basePremiumAmount는 월납 원액(×12 하지 않음)이다 — 100,000 × 12 = 1,200,000이 한도(limitAmount)다.
        row.setBasePremiumAmount(new BigDecimal("100000"));
        row.setRefund12mAmount(BigDecimal.ZERO);
        row.setComplianceDeductionAmount(BigDecimal.ZERO);
        row.setLimitAmount(new BigDecimal("1200000"));
        row.setIncludedAmount(new BigDecimal("650000"));
        row.setRemainingAmount(new BigDecimal("550000"));
        row.setUsagePct(new BigDecimal("54.166667"));
        row.setResultStatus("NORMAL");
        row.setCalculationSnapshotJson("{\"premiumMultiplier\":\"12.0000\"}");

        List<CapCheckDetailLine> details = List.of(
                new CapCheckDetailLine(
                        1, 1L, "BASE_COMMISSION", "FC 기본수수료", 11L, 1, "INCLUDED", new BigDecimal("650000"), "산입", null),
                new CapCheckDetailLine(
                        2, 2L, "EDU_SUPPORT", "교육비", null, 1, "EXCLUDED", new BigDecimal("50000"), "제외", "SRC-004"));

        when(capCheckMapper.findById(999L)).thenReturn(row);
        when(capCheckMapper.findDetailsByCapCheckId(999L)).thenReturn(details);

        CapCheckBasisResponse basis = capCheckService.findDetail(999L).orElseThrow();

        assertThat(basis.capCheck().capCheckId()).isEqualTo(999L);
        assertThat(basis.capCheck().contractNo()).isEqualTo("C001");
        assertThat(basis.capCheck().capRuleSetId()).isEqualTo(500L);
        assertThat(basis.capCheck().limitAmount()).isEqualTo(1_200_000L);
        assertThat(basis.capCheck().includedAmount()).isEqualTo(650_000L);
        assertThat(basis.details()).hasSize(2);
        assertThat(basis.details().get(0).commissionItemName()).isEqualTo("FC 기본수수료");
        assertThat(basis.details().get(0).classificationSnapshot()).isEqualTo("INCLUDED");
        assertThat(basis.calculationSnapshot()).containsEntry("premiumMultiplier", "12.0000");
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

    @Test
    void searchRejectsPageBelowOne() {
        CapCheckSearchCriteria criteria = new CapCheckSearchCriteria(null, null, null, null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> capCheckService.search(criteria, 0, 20))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.COMMON_002);
    }

    @Test
    void searchRejectsSizeBelowOne() {
        CapCheckSearchCriteria criteria = new CapCheckSearchCriteria(null, null, null, null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> capCheckService.search(criteria, 1, 0))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.COMMON_002);
    }

    @Test
    void searchRejectsSizeAboveOneHundred() {
        CapCheckSearchCriteria criteria = new CapCheckSearchCriteria(null, null, null, null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> capCheckService.search(criteria, 1, 101))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.COMMON_002);
    }

    @SuppressWarnings("unchecked")
    private static List<com.susukkang.fgc.cap.dto.CapCheckDetailInsertRow> argThatDetailListHasCapCheckId(Long capCheckId) {
        return org.mockito.ArgumentMatchers.argThat(list ->
                list != null && list.stream().allMatch(d -> capCheckId.equals(d.getCapCheckId())));
    }
}
