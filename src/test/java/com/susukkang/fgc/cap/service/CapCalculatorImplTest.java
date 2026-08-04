package com.susukkang.fgc.cap.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapContractView;
import com.susukkang.fgc.cap.dto.CapRuleItemView;
import com.susukkang.fgc.cap.dto.CapRuleSetView;
import com.susukkang.fgc.cap.dto.RefundRateQuery;
import com.susukkang.fgc.cap.dto.RefundRateResolution;
import com.susukkang.fgc.cap.dto.ScheduleAmountView;
import com.susukkang.fgc.cap.mapper.CapCheckMapper;
import com.susukkang.fgc.cap.mapper.CapContractMapper;
import com.susukkang.fgc.cap.mapper.CapRuleMapper;
import com.susukkang.fgc.cap.mapper.CapScheduleAmountMapper;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FGC-FUN-030 초년도 수수료 한도 계산 엔진 단위테스트.
 * DB 없이 매퍼를 mock 으로 대체해 계산식 자체(기본식·80% 가산·준법경영비 공제·산입 분류·판정)를 검증한다.
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
    private CapCheckMapper capCheckMapper;
    @Mock
    private ProductRefundRateResolver refundRateResolver;

    private CapCalculatorImpl capCalculator;

    @BeforeEach
    void setUp() {
        capCalculator = new CapCalculatorImpl(capContractMapper, capRuleMapper, capScheduleAmountMapper,
                capCheckMapper, refundRateResolver, new ObjectMapper());

        // cap_check INSERT 는 생성된 PK 를 row 에 되채워 준다 — 실제 IDENTITY 컬럼 동작을 흉내낸다.
        lenient().doAnswer(invocation -> {
            com.susukkang.fgc.cap.dto.CapCheckInsertRow row = invocation.getArgument(0);
            row.setCapCheckId(999L);
            return null;
        }).when(capCheckMapper).insertCapCheck(any());
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

    @Test
    void 월납_100000원_일반_샘플의_기본_한도는_1200000원이다() {
        when(capContractMapper.findById(CONTRACT_ID))
                .thenReturn(contract(LocalDate.of(2026, 7, 10), new BigDecimal("100000"), false));
        when(capRuleMapper.findApplicableRuleSet(eq("GA_TO_FC"), any(), any(), any(), any()))
                .thenReturn(ruleSet("GA_TO_FC", BigDecimal.ZERO, "STANDARD_DEDUCTION_80"));
        when(capRuleMapper.findRuleItems(CAP_RULE_SET_ID)).thenReturn(List.of());
        when(capScheduleAmountMapper.findFirstYearScheduleAmounts(anyLong(), anyString(), anyInt())).thenReturn(List.of());

        CapCalculationResult result = capCalculator.calculate(CapCalculationCommand.realtime(
                CONTRACT_ID, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        assertThat(result.limitAmount()).isEqualByComparingTo("1200000");
        assertThat(result.basePremiumAmount()).isEqualByComparingTo("1200000");
        assertThat(result.refund12mAmount()).isEqualByComparingTo("0");
        assertThat(result.includedAmount()).isEqualByComparingTo("0");
        assertThat(result.resultStatus()).isEqualTo(CapResultStatus.NORMAL);
        assertThat(result.capCheckId()).isEqualTo(999L);
    }

    @Test
    void 표준해약공제액_80퍼센트_이상_공제_대상은_12차월_예상해약환급률이_한도에_가산된다() {
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

    @Test
    void 원수사_GA_단계는_준법경영비_3퍼센트가_한도에서_공제된다() {
        when(capContractMapper.findById(CONTRACT_ID))
                .thenReturn(contract(LocalDate.of(2026, 1, 15), new BigDecimal("100000"), true));
        when(capRuleMapper.findApplicableRuleSet(eq("INSURER_TO_GA"), any(), any(), any(), any()))
                .thenReturn(ruleSet("INSURER_TO_GA", new BigDecimal("3.0000"), "STANDARD_DEDUCTION_80"));
        when(capRuleMapper.findRuleItems(CAP_RULE_SET_ID)).thenReturn(List.of());
        when(capScheduleAmountMapper.findFirstYearScheduleAmounts(anyLong(), anyString(), anyInt())).thenReturn(List.of());
        when(refundRateResolver.resolve(any(RefundRateQuery.class)))
                .thenReturn(Optional.of(new RefundRateResolution(777L, 55L, 3, new BigDecimal("24.000000"))));

        CapCalculationResult result = capCalculator.calculate(CapCalculationCommand.realtime(
                CONTRACT_ID, PaymentStage.INSURER_TO_GA, LocalDate.of(2026, 1, 15)));

        // gross 1,488,000 × 3% = 44,640 공제 → 1,443,360
        assertThat(result.complianceDeductionAmount()).isEqualByComparingTo("44640");
        assertThat(result.limitAmount()).isEqualByComparingTo("1443360");
    }

    @Test
    void 계약일이_2026_2027_2029이어도_같은_엔진이_같은_기본한도를_계산한다() {
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

    @Test
    void 산입_제외_항목이_섞이면_INCLUDED만_합산되고_초과시_VIOLATION이다() {
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

    @Test
    void 룰셋에_없는_항목이나_환급률표_미존재는_REVIEW_REQUIRED이다() {
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

    @Test
    void 사용률이_경고기준_이상이면_WARNING이다() {
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

    @Test
    void 환급률표_조회는_검증실행일이_아니라_계약일을_기준으로_한다() {
        // MONTHLY 재검증은 asOfDate(검증 실행일)가 계약일보다 훨씬 뒤일 수 있다.
        // 그래도 REG-19 상 1,200% 적용 규칙의 기준일은 계약 체결일이므로,
        // 환급률표 조회는 계약일로 해야 한다(asOfDate 로 하면 재검증 시점에 활성화된
        // 더 최신 표가 선택될 위험이 있다).
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

    @Test
    void 존재하지_않는_계약이면_예외가_발생한다() {
        when(capContractMapper.findById(CONTRACT_ID)).thenReturn(null);

        assertThatThrownBy(() -> capCalculator.calculate(
                CapCalculationCommand.realtime(CONTRACT_ID, PaymentStage.GA_TO_FC, LocalDate.now())))
                .isInstanceOf(FgcBusinessException.class);
    }
}
