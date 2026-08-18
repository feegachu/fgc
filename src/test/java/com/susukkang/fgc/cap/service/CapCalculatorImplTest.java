package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapContractView;
import com.susukkang.fgc.cap.dto.CapRuleItemView;
import com.susukkang.fgc.cap.dto.CapRuleSetView;
import com.susukkang.fgc.cap.dto.RefundRateQuery;
import com.susukkang.fgc.cap.dto.RefundRateResolution;
import com.susukkang.fgc.cap.dto.ScheduleAmountView;
import com.susukkang.fgc.cap.mapper.CapContractMapper;
import com.susukkang.fgc.cap.mapper.CapRuleMapper;
import com.susukkang.fgc.cap.mapper.CapScheduleAmountMapper;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 설명 : FGC-FUN-030 초년도 수수료 한도 계산 엔진 단위 테스트
 * DB 없이 매퍼를 mock 으로 대체해 계산식 자체(기본식·80% 가산·준법경영비 공제·산입 분류·판정)를 검증한다.
 *
 * @author yslee
 * @since 2026-08-10
 * @version 1.2
 */
@ExtendWith(MockitoExtension.class)
class CapCalculatorImplTest {

    private static final Long CONTRACT_ID = 1L;
    private static final Long INSURER_ID = 10L;
    private static final Long PRODUCT_ID = 100L;
    private static final Long CAP_RULE_SET_ID = 500L;
    private static final String CHANNEL = "FACE_TO_FACE";
    private static final String PRODUCT_GROUP = "HEALTH_PROTECTION";

    @Mock
    private CapContractMapper capContractMapper;
    @Mock
    private CapRuleMapper capRuleMapper;
    @Mock
    private CapScheduleAmountMapper capScheduleAmountMapper;
    @Mock
    private ProductRefundRateResolver refundRateResolver;

    private CapCalculatorImpl capCalculator;

    @BeforeEach
    void setUp() {
        capCalculator = new CapCalculatorImpl(capContractMapper, capRuleMapper, capScheduleAmountMapper,
                refundRateResolver);
    }

    private CapContractView contract(LocalDate contractDate, BigDecimal monthlyEquivalent,
                                      boolean standardDeduction80Yn) {
        CapContractView v = new CapContractView();
        v.setContractId(CONTRACT_ID);
        v.setContractDate(contractDate);
        v.setMonthlyEquivalentFirstPremium(monthlyEquivalent);
        v.setPaymentTermMonths(240);
        v.setInsurerId(INSURER_ID);
        v.setProductId(PRODUCT_ID);
        v.setProductOfferingId(1000L);
        v.setChannelCode(CHANNEL);
        v.setProductGroupCode(PRODUCT_GROUP);
        v.setStandardDeduction80Yn(standardDeduction80Yn);
        return v;
    }

    private CapRuleSetView ruleSet(String paymentStage, BigDecimal complianceDeductionPct,
                                    String refundAdditionCondition) {
        CapRuleSetView v = new CapRuleSetView();
        v.setCapRuleSetId(CAP_RULE_SET_ID);
        v.setPolicyVersionId(1L);
        v.setPaymentStage(paymentStage);
        v.setContractDateFrom(LocalDate.of(2021, 1, 1));
        v.setContractDateTo(null);
        v.setFirstYearMonths(12);
        v.setPremiumMultiplier(new BigDecimal("12.0000"));
        v.setComplianceDeductionPct(complianceDeductionPct);
        v.setRefundAdditionCondition(refundAdditionCondition);
        v.setWarningUsagePct(new BigDecimal("90.0000"));
        return v;
    }

    private CapRuleItemView includedItem(Long commissionItemId, String code) {
        CapRuleItemView i = new CapRuleItemView();
        i.setCommissionItemId(commissionItemId);
        i.setItemCode(code);
        i.setInclusionStatus("INCLUDED");
        i.setDecisionReason("테스트 산입");
        return i;
    }

    private ScheduleAmountView scheduleLine(Long lineId, Long commissionItemId, int monthNo, String amount) {
        ScheduleAmountView s = new ScheduleAmountView();
        s.setScheduleLineId(lineId);
        s.setCommissionItemId(commissionItemId);
        s.setContractMonthNo(monthNo);
        s.setAmount(new BigDecimal(amount));
        return s;
    }

    // 월납 100,000원 일반 샘플의 기본 한도는 1,200,000원이다
    @Test
    void basicLimitIsMonthlyPremiumTimesTwelveForGeneralSample() {
        when(capContractMapper.findById(CONTRACT_ID))
                .thenReturn(contract(LocalDate.of(2026, 7, 10), new BigDecimal("100000"), false));
        when(capRuleMapper.findApplicableRuleSet(eq("GA_TO_FC"), any(), any(), any(), any()))
                .thenReturn(ruleSet("GA_TO_FC", BigDecimal.ZERO, "STANDARD_DEDUCTION_80"));
        when(capRuleMapper.findRuleItems(CAP_RULE_SET_ID)).thenReturn(List.of());
        when(capScheduleAmountMapper.findFirstYearScheduleAmounts(anyLong(), anyString(), anyInt())).thenReturn(List.of());

        CapCalculationResult result = capCalculator.calculate(CapCalculationCommand.realtime(
                CONTRACT_ID, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        assertThat(result.limitAmount()).isEqualByComparingTo("1200000");
        // basePremiumAmount는 월납 원액이다(×12 하지 않음) — 한도(1,200,000)와는 다른 값이다
        assertThat(result.basePremiumAmount()).isEqualByComparingTo("100000");
        assertThat(result.refund12mAmount()).isEqualByComparingTo("0");
        assertThat(result.includedAmount()).isEqualByComparingTo("0");
        assertThat(result.resultStatus()).isEqualTo(CapResultStatus.NORMAL);
    }

    // 표준해약공제액 80% 이상 공제 대상은 12차월 예상해약환급률이 한도에 가산된다
    @Test
    void addsMonth12RefundRateToLimitForStandardDeduction80Product() {
        when(capContractMapper.findById(CONTRACT_ID))
                .thenReturn(contract(LocalDate.of(2026, 1, 15), new BigDecimal("100000"), true));
        when(capRuleMapper.findApplicableRuleSet(eq("GA_TO_FC"), any(), any(), any(), any()))
                .thenReturn(ruleSet("GA_TO_FC", BigDecimal.ZERO, "STANDARD_DEDUCTION_80"));
        when(capRuleMapper.findRuleItems(CAP_RULE_SET_ID)).thenReturn(List.of());
        when(capScheduleAmountMapper.findFirstYearScheduleAmounts(anyLong(), anyString(), anyInt())).thenReturn(List.of());
        when(refundRateResolver.resolve(any(RefundRateQuery.class)))
                .thenReturn(Optional.of(new RefundRateResolution(777L, 55L, 3, new BigDecimal("24.000000"))));

        CapCalculationResult result = capCalculator.calculate(CapCalculationCommand.realtime(
                CONTRACT_ID, PaymentStage.GA_TO_FC, LocalDate.of(2026, 1, 15)));

        // base 1,200,000 × 24% = 288,000 → limit 1,488,000
        assertThat(result.refund12mAmount()).isEqualByComparingTo("288000");
        assertThat(result.limitAmount()).isEqualByComparingTo("1488000");
        assertThat(result.refundRateTableId()).isEqualTo(777L);
        assertThat(result.resultStatus()).isEqualTo(CapResultStatus.NORMAL);
    }

    // 2026-08-10 yslee - 준법경영비 실제 증빙액·상한·누락 회귀 시나리오 추가
    // 기존 코드: 증빙 입력 없이 월납 초회보험료의 3% 전액 공제를 정상으로 단정
    // 문제: REG-10의 실제 지급·증빙 금액 기준과 최대 허용률 의미를 검증하지 못함
    // 개선: 실제액 미만·상한 초과·증빙 누락·GA_TO_FC 미적용을 각각 검증
    @Test
    void deductsOnlyVerifiedComplianceAmountBelowMaximum() {
        stubComplianceCalculation("INSURER_TO_GA", new BigDecimal("3.0000"));

        CapCalculationResult result = capCalculator.calculate(CapCalculationCommand.realtime(
                CONTRACT_ID,
                PaymentStage.INSURER_TO_GA,
                LocalDate.of(2026, 1, 15),
                new BigDecimal("2000")
        ));

        assertThat(result.complianceDeductionAmount()).isEqualByComparingTo("2000");
        assertThat(result.limitAmount()).isEqualByComparingTo("1486000");
        assertThat(result.calculationSnapshot().get("complianceMaximumAmount").toString()).isEqualTo("3000");
    }

    @Test
    void capsVerifiedComplianceAmountAtMaximum() {
        stubComplianceCalculation("INSURER_TO_GA", new BigDecimal("3.0000"));

        CapCalculationResult result = capCalculator.calculate(CapCalculationCommand.realtime(
                CONTRACT_ID,
                PaymentStage.INSURER_TO_GA,
                LocalDate.of(2026, 1, 15),
                new BigDecimal("5000")
        ));

        assertThat(result.complianceDeductionAmount()).isEqualByComparingTo("3000");
        assertThat(result.limitAmount()).isEqualByComparingTo("1485000");
    }

    @Test
    void requiresReviewWhenComplianceEvidenceIsMissing() {
        stubComplianceCalculation("INSURER_TO_GA", new BigDecimal("3.0000"));

        CapCalculationResult result = capCalculator.calculate(CapCalculationCommand.realtime(
                CONTRACT_ID, PaymentStage.INSURER_TO_GA, LocalDate.of(2026, 1, 15)));

        assertThat(result.complianceDeductionAmount()).isEqualByComparingTo("0");
        assertThat(result.resultStatus()).isEqualTo(CapResultStatus.REVIEW_REQUIRED);
    }

    @Test
    void ignoresComplianceEvidenceForGaToFcStage() {
        when(capContractMapper.findById(CONTRACT_ID))
                .thenReturn(contract(LocalDate.of(2026, 1, 15), new BigDecimal("100000"), false));
        when(capRuleMapper.findApplicableRuleSet(eq("GA_TO_FC"), any(), any(), any(), any()))
                .thenReturn(ruleSet("GA_TO_FC", BigDecimal.ZERO, "NONE"));
        when(capRuleMapper.findRuleItems(CAP_RULE_SET_ID)).thenReturn(List.of());
        when(capScheduleAmountMapper.findFirstYearScheduleAmounts(anyLong(), anyString(), anyInt())).thenReturn(List.of());

        CapCalculationResult result = capCalculator.calculate(CapCalculationCommand.realtime(
                CONTRACT_ID,
                PaymentStage.GA_TO_FC,
                LocalDate.of(2026, 1, 15),
                new BigDecimal("5000")
        ));

        assertThat(result.complianceDeductionAmount()).isEqualByComparingTo("0");
        assertThat(result.resultStatus()).isEqualTo(CapResultStatus.NORMAL);
    }

    private void stubComplianceCalculation(String stage, BigDecimal maximumPct) {
        when(capContractMapper.findById(CONTRACT_ID))
                .thenReturn(contract(LocalDate.of(2026, 1, 15), new BigDecimal("100000"), true));
        when(capRuleMapper.findApplicableRuleSet(eq(stage), any(), any(), any(), any()))
                .thenReturn(ruleSet(stage, maximumPct, "STANDARD_DEDUCTION_80"));
        when(capRuleMapper.findRuleItems(CAP_RULE_SET_ID)).thenReturn(List.of());
        when(capScheduleAmountMapper.findFirstYearScheduleAmounts(anyLong(), anyString(), anyInt())).thenReturn(List.of());
        when(refundRateResolver.resolve(any(RefundRateQuery.class)))
                .thenReturn(Optional.of(new RefundRateResolution(777L, 55L, 3, new BigDecimal("24.000000"))));
    }

    // 계약일이 2026/2027/2029이어도 같은 엔진이 같은 기본한도를 계산한다
    @Test
    void calculatesSameBasicLimitAcrossContractYears() {
        List<LocalDate> contractDates = List.of(
                LocalDate.of(2026, 7, 1),   // 2026년 현행
                LocalDate.of(2027, 3, 2),   // 2027년 4년 분급 시행 이후
                LocalDate.of(2029, 5, 20)   // 2029년 7년 분급 시행 이후
        );

        for (LocalDate contractDate : contractDates) {
            when(capContractMapper.findById(CONTRACT_ID))
                    .thenReturn(contract(contractDate, new BigDecimal("100000"), false));
            when(capRuleMapper.findApplicableRuleSet(eq("GA_TO_FC"), eq(contractDate), any(), any(), any()))
                    .thenReturn(ruleSet("GA_TO_FC", BigDecimal.ZERO, "STANDARD_DEDUCTION_80"));
            when(capRuleMapper.findRuleItems(CAP_RULE_SET_ID)).thenReturn(List.of());
            when(capScheduleAmountMapper.findFirstYearScheduleAmounts(anyLong(), anyString(), anyInt()))
                    .thenReturn(List.of());

            CapCalculationResult result = capCalculator.calculate(
                    CapCalculationCommand.realtime(CONTRACT_ID, PaymentStage.GA_TO_FC, contractDate));

            assertThat(result.limitAmount())
                    .as("계약일 %s 의 기본 한도", contractDate)
                    .isEqualByComparingTo("1200000");
        }
    }

    // 산입/제외 항목이 섞이면 INCLUDED만 합산되고 초과 시 VIOLATION이다
    @Test
    void sumsOnlyIncludedItemsAndFlagsViolationWhenExceeded() {
        when(capContractMapper.findById(CONTRACT_ID))
                .thenReturn(contract(LocalDate.of(2026, 7, 10), new BigDecimal("100000"), false));
        when(capRuleMapper.findApplicableRuleSet(eq("GA_TO_FC"), any(), any(), any(), any()))
                .thenReturn(ruleSet("GA_TO_FC", BigDecimal.ZERO, "STANDARD_DEDUCTION_80"));

        CapRuleItemView included = includedItem(1L, "BASE_COMMISSION");
        CapRuleItemView excluded = new CapRuleItemView();
        excluded.setCommissionItemId(2L);
        excluded.setItemCode("NEWCOMER_SUPPORT");
        excluded.setInclusionStatus("EXCLUDED");
        excluded.setExclusionType("NEWCOMER_SUPPORT");
        excluded.setDecisionReason("신인활동지원비 제외");

        when(capRuleMapper.findRuleItems(CAP_RULE_SET_ID)).thenReturn(List.of(included, excluded));
        when(capScheduleAmountMapper.findFirstYearScheduleAmounts(anyLong(), anyString(), anyInt())).thenReturn(List.of(
                scheduleLine(11L, 1L, 1, "1250000"),   // INCLUDED, 한도(1,200,000) 초과
                scheduleLine(12L, 2L, 1, "500000")     // EXCLUDED, 합산 제외
        ));

        CapCalculationResult result = capCalculator.calculate(CapCalculationCommand.realtime(
                CONTRACT_ID, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        assertThat(result.includedAmount()).isEqualByComparingTo("1250000");
        assertThat(result.remainingAmount()).isEqualByComparingTo("-50000");
        assertThat(result.resultStatus()).isEqualTo(CapResultStatus.VIOLATION);
        assertThat(result.details()).hasSize(2);
    }

    // 룰셋에 없는 항목이나 환급률표 미존재는 REVIEW_REQUIRED다
    @Test
    void flagsReviewRequiredWhenItemUnclassifiedOrRefundTableMissing() {
        when(capContractMapper.findById(CONTRACT_ID))
                .thenReturn(contract(LocalDate.of(2026, 7, 10), new BigDecimal("100000"), true));
        when(capRuleMapper.findApplicableRuleSet(eq("GA_TO_FC"), any(), any(), any(), any()))
                .thenReturn(ruleSet("GA_TO_FC", BigDecimal.ZERO, "STANDARD_DEDUCTION_80"));
        when(capRuleMapper.findRuleItems(CAP_RULE_SET_ID)).thenReturn(List.of());
        when(capScheduleAmountMapper.findFirstYearScheduleAmounts(anyLong(), anyString(), anyInt())).thenReturn(List.of(
                scheduleLine(11L, 99L, 1, "100000")   // 룰셋에 없는 항목
        ));
        when(refundRateResolver.resolve(any(RefundRateQuery.class))).thenReturn(Optional.empty());

        CapCalculationResult result = capCalculator.calculate(CapCalculationCommand.realtime(
                CONTRACT_ID, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        assertThat(result.resultStatus()).isEqualTo(CapResultStatus.REVIEW_REQUIRED);
        assertThat(result.refund12mAmount()).isEqualByComparingTo("0");
    }

    // 사용률이 경고기준 이상이면 WARNING이다
    @Test
    void flagsWarningWhenUsageAtOrAboveThreshold() {
        when(capContractMapper.findById(CONTRACT_ID))
                .thenReturn(contract(LocalDate.of(2026, 7, 10), new BigDecimal("100000"), false));
        when(capRuleMapper.findApplicableRuleSet(eq("GA_TO_FC"), any(), any(), any(), any()))
                .thenReturn(ruleSet("GA_TO_FC", BigDecimal.ZERO, "STANDARD_DEDUCTION_80"));
        when(capRuleMapper.findRuleItems(CAP_RULE_SET_ID)).thenReturn(List.of(includedItem(1L, "BASE_COMMISSION")));
        // 한도 1,200,000 의 91.666...% = 1,100,000
        when(capScheduleAmountMapper.findFirstYearScheduleAmounts(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of(scheduleLine(11L, 1L, 1, "1100000")));

        CapCalculationResult result = capCalculator.calculate(CapCalculationCommand.realtime(
                CONTRACT_ID, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        assertThat(result.resultStatus()).isEqualTo(CapResultStatus.WARNING);
    }

    // 환급률표 조회는 검증실행일이 아니라 계약일을 기준으로 한다
    // (MONTHLY 재검증의 asOfDate는 계약일보다 뒤일 수 있으나, REG-19상 기준일은 계약 체결일이다)
    @Test
    void resolvesRefundRateByContractDateNotValidationAsOfDate() {
        LocalDate contractDate = LocalDate.of(2026, 1, 15);
        LocalDate validationAsOfDate = LocalDate.of(2026, 9, 1);

        when(capContractMapper.findById(CONTRACT_ID))
                .thenReturn(contract(contractDate, new BigDecimal("100000"), true));
        when(capRuleMapper.findApplicableRuleSet(eq("GA_TO_FC"), any(), any(), any(), any()))
                .thenReturn(ruleSet("GA_TO_FC", BigDecimal.ZERO, "STANDARD_DEDUCTION_80"));
        when(capRuleMapper.findRuleItems(CAP_RULE_SET_ID)).thenReturn(List.of());
        when(capScheduleAmountMapper.findFirstYearScheduleAmounts(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(refundRateResolver.resolve(any(RefundRateQuery.class)))
                .thenReturn(Optional.of(new RefundRateResolution(777L, 55L, 3, new BigDecimal("24.000000"))));

        capCalculator.calculate(new CapCalculationCommand(
                CONTRACT_ID, PaymentStage.GA_TO_FC, validationAsOfDate, CapCheckKind.MONTHLY, 900L));

        ArgumentCaptor<RefundRateQuery> captor = ArgumentCaptor.forClass(RefundRateQuery.class);
        verify(refundRateResolver).resolve(captor.capture());
        assertThat(captor.getValue().asOfDate()).isEqualTo(contractDate);
    }

    // schedule_line.expected_amount 는 numeric(15,2)라 원 미만 값이 들어올 수 있다 — 산입액에
    // 합산되기 전에 각 행을 먼저 원 단위 HALF_UP 반올림해야 한다 (100.5 + 100.5 는 200이 아니라
    // 101 + 101 = 202여야 한다)
    @Test
    void roundsEachScheduleLineToWonBeforeSummingIncludedAmount() {
        when(capContractMapper.findById(CONTRACT_ID))
                .thenReturn(contract(LocalDate.of(2026, 7, 10), new BigDecimal("100000"), false));
        when(capRuleMapper.findApplicableRuleSet(eq("GA_TO_FC"), any(), any(), any(), any()))
                .thenReturn(ruleSet("GA_TO_FC", BigDecimal.ZERO, "STANDARD_DEDUCTION_80"));
        when(capRuleMapper.findRuleItems(CAP_RULE_SET_ID)).thenReturn(List.of(includedItem(1L, "BASE_COMMISSION")));
        when(capScheduleAmountMapper.findFirstYearScheduleAmounts(anyLong(), anyString(), anyInt())).thenReturn(List.of(
                scheduleLine(11L, 1L, 1, "100.5"),
                scheduleLine(12L, 1L, 2, "100.5")
        ));

        CapCalculationResult result = capCalculator.calculate(CapCalculationCommand.realtime(
                CONTRACT_ID, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        assertThat(result.includedAmount()).isEqualByComparingTo("202");
        assertThat(result.details()).extracting(d -> d.amount().toPlainString())
                .containsExactly("101", "101");
    }

    // 존재하지 않는 계약이면 예외가 발생한다
    @Test
    void throwsExceptionWhenContractNotFound() {
        when(capContractMapper.findById(CONTRACT_ID)).thenReturn(null);

        assertThatThrownBy(() -> capCalculator.calculate(
                CapCalculationCommand.realtime(CONTRACT_ID, PaymentStage.GA_TO_FC, LocalDate.now())))
                .isInstanceOf(FgcBusinessException.class);
    }

    @Test
    void throwsCapRuleMissingWhenApplicableRuleSetDoesNotExist() {
        when(capContractMapper.findById(CONTRACT_ID)).thenReturn(
                contract(LocalDate.of(2026, 7, 10), new BigDecimal("100000"), false));
        when(capRuleMapper.findApplicableRuleSet(eq("GA_TO_FC"), any(), any(), any(), any()))
                .thenReturn(null);

        assertThatThrownBy(() -> capCalculator.calculate(
                CapCalculationCommand.realtime(
                        CONTRACT_ID,
                        PaymentStage.GA_TO_FC,
                        LocalDate.of(2026, 7, 10))))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.CAP_004));
    }
}
