package com.susukkang.fgc.cap;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.RefundRateQuery;
import com.susukkang.fgc.cap.dto.RefundRateResolution;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.cap.service.ProductRefundRateResolver;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 로컬 PostgreSQL(docker-compose fgc-db)에 적용된 시드데이터(V3/V4)를 대상으로
 * CapCalculator/ProductRefundRateResolver 매퍼 SQL이 실제로 맞물려 동작하는지 검증한다.
 *
 * schedule_line 은 ScheduleGenerator(FGC-FUN-012/013/018)가 아직 없어 seed 에 없으므로,
 * 이 테스트가 직접 최소한의 예상 스케줄 1건을 만들어 넣는다. 트랜잭션은 끝나면 롤백된다.
 */
@SpringBootTest
@Transactional
class CapCalculatorIntegrationTest {

    @Autowired
    private CapCalculator capCalculator;
    @Autowired
    private ProductRefundRateResolver refundRateResolver;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long contractId(String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, contractNo);
    }

    private void insertOperationalScheduleWithOneBaseCommissionLine(Long contractId, LocalDate contractDate) {
        Long policyVersionId = jdbcTemplate.queryForObject(
                "SELECT policy_version_id FROM fgc.policy_version WHERE policy_code='GA-CUR-2026-V1' AND status='ACTIVE'",
                Long.class);
        Long baseCommissionItemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item WHERE item_code='BASE_COMMISSION'",
                Long.class);

        Long scheduleHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header
                    (contract_id, payment_stage, policy_version_id, schedule_version_no, schedule_regime)
                VALUES (?, 'GA_TO_FC', ?, 1, 'CURRENT')
                RETURNING schedule_header_id
                """, Long.class, contractId, policyVersionId);

        jdbcTemplate.update("""
                INSERT INTO fgc.schedule_line
                    (schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                     commission_item_id, basis_code, basis_amount, calculation_type, rate_pct, expected_amount)
                VALUES (?, 1, 1, 1, ?, ?, 'MONTHLY_EQUIVALENT_FIRST_PREMIUM', 100000, 'RATE', 650.000000, 650000)
                """, scheduleHeaderId, contractDate, baseCommissionItemId);
    }

    // C001(일반계약)은 기본한도 1,200,000원에 산입액이 반영된다
    @Test
    void generalContractC001ReflectsIncludedAmountInBasicLimit() {
        Long id = contractId("FGC-FGL01-202607-0001"); // STD-LIFE-A, 월납 100,000원, 80% 공제 아님
        insertOperationalScheduleWithOneBaseCommissionLine(id, LocalDate.of(2026, 7, 10));

        CapCalculationResult result = capCalculator.calculate(
                CapCalculationCommand.realtime(id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        assertThat(result.basePremiumAmount()).isEqualByComparingTo("1200000");
        assertThat(result.refund12mAmount()).isEqualByComparingTo("0");
        assertThat(result.limitAmount()).isEqualByComparingTo("1200000");
        assertThat(result.includedAmount()).isEqualByComparingTo("650000");
        assertThat(result.remainingAmount()).isEqualByComparingTo("550000");
        assertThat(result.resultStatus()).isEqualTo(CapResultStatus.NORMAL);
        assertThat(result.capCheckId()).isNotNull();

        Integer detailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.cap_check_detail WHERE cap_check_id = ?",
                Integer.class, result.capCheckId());
        assertThat(detailCount).isEqualTo(1);
    }

    // A1(80% 공제대상 상품)은 12차월 예상해약환급률표가 한도에 가산된다
    // FGC-FGL02-202601-0001 : STD-LIFE-B(80% 공제 대상), 월납 100,000원, 240개월납, 계약일 2026-01-15
    // GA_TO_FC 1,200% 룰셋은 2026-07-01부터 적용되므로(REG-CAP-GA-2026-V1), 이 계약은
    // INSURER_TO_GA 단계로 검증한다(REG-CAP-INS-2026-V1 은 2021-01-01부터 적용).
    @Test
    void standardDeduction80ProductA1AddsMonth12RefundRateToLimit() {
        Long id = contractId("FGC-FGL02-202601-0001");

        CapCalculationResult result = capCalculator.calculate(
                CapCalculationCommand.realtime(id, PaymentStage.INSURER_TO_GA, LocalDate.of(2026, 1, 15)));

        // seed 공식: GREATEST(0,(12-2)*2.4) = 24.0% → 1,200,000 × 24% = 288,000
        assertThat(result.refund12mAmount()).isEqualByComparingTo("288000");
        // gross 1,488,000 에서 준법경영비 3%(44,640) 공제 → 1,443,360
        assertThat(result.complianceDeductionAmount()).isEqualByComparingTo("44640");
        assertThat(result.limitAmount()).isEqualByComparingTo("1443360");
        assertThat(result.refundRateTableId()).isNotNull();
    }

    // ProductRefundRateResolver는 STD-LIFE-B의 12차월 환급률과 표버전을 돌려준다
    @Test
    void resolverReturnsMonth12RateAndTableVersionForStdLifeB() {
        Long insurerId = jdbcTemplate.queryForObject(
                "SELECT insurer_id FROM fgc.insurer WHERE insurer_code='FGL02'", Long.class);
        Long productId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM fgc.product WHERE standard_product_code='STD-LIFE-B'", Long.class);

        Optional<RefundRateResolution> resolution = refundRateResolver.resolve(
                new RefundRateQuery(insurerId, productId, 240, "FACE_TO_FACE", LocalDate.of(2026, 1, 15)));

        assertThat(resolution).isPresent();
        assertThat(resolution.get().month12RatePct()).isEqualByComparingTo("24.000000");
        assertThat(resolution.get().versionNo()).isEqualTo(1);
    }

    // 계약일이 다른 2026/2027 계약도 같은 엔진으로 동일 기본한도를 계산한다
    @Test
    void calculatesSameBasicLimitForContractsInDifferentYears() {
        // C001(2026-07-10)과 G1(2027-03-02)은 둘 다 STD-LIFE-A · 월납 100,000원이다.
        Long c2026 = contractId("FGC-FGL01-202607-0001");
        Long c2027 = contractId("FGC-FGL01-202703-0001");

        CapCalculationResult r2026 = capCalculator.calculate(
                CapCalculationCommand.realtime(c2026, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));
        CapCalculationResult r2027 = capCalculator.calculate(
                CapCalculationCommand.realtime(c2027, PaymentStage.GA_TO_FC, LocalDate.of(2027, 3, 2)));

        assertThat(r2026.limitAmount()).isEqualByComparingTo(r2027.limitAmount());
        assertThat(r2027.limitAmount()).isEqualByComparingTo("1200000");
    }
}
