package com.susukkang.fgc.cap;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapCheckBasisResponse;
import com.susukkang.fgc.cap.dto.CapCheckListRow;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.cap.dto.CapCheckSearchCriteria;
import com.susukkang.fgc.cap.dto.CapCheckSearchResult;
import com.susukkang.fgc.cap.dto.RefundRateQuery;
import com.susukkang.fgc.cap.dto.RefundRateResolution;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.cap.service.CapCheckService;
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
 * 설명 : 실제 PostgreSQL 정책·스케줄·준법경영비 계산 통합 테스트
 * 실제 로컬 PostgreSQL(docker-compose fgc-db)에 적용된 시드데이터(V3/V4)를 대상으로
 * CapCalculator/ProductRefundRateResolver 매퍼 SQL이 실제로 맞물려 동작하는지 검증한다.
 *
 * schedule_line 은 ScheduleGenerator(FGC-FUN-012/013/018)가 아직 없어 seed 에 없으므로,
 * 이 테스트가 직접 최소한의 예상 스케줄 1건을 만들어 넣는다. 트랜잭션은 끝나면 롤백된다.
 *
 * @author yslee
 * @since 2026-08-10
 * @version 1.2
 */
@SpringBootTest
@Transactional
class CapCalculatorIntegrationTest {

    @Autowired
    private CapCalculator capCalculator;
    @Autowired
    private CapCheckService capCheckService;
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

        // findFirstYearScheduleAmounts는 schedule_purpose='OPERATIONAL', active_yn=true인 헤더만
        // 본다. 둘 다 컬럼 기본값과 같지만, 기본값이 나중에 바뀌어도 이 테스트가 계속 맞는 헤더를
        // 만들도록 명시적으로 값을 넣는다.
        Long scheduleHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header
                    (contract_id, payment_stage, policy_version_id, schedule_version_no, schedule_regime,
                     schedule_purpose, active_yn)
                VALUES (?, 'GA_TO_FC', ?, 1, 'CURRENT', 'OPERATIONAL', true)
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

        // basePremiumAmount는 월납 원액이다(×12 하지 않음) — 한도(1,200,000)와는 다른 값이다
        assertThat(result.basePremiumAmount()).isEqualByComparingTo("100000");
        assertThat(result.refund12mAmount()).isEqualByComparingTo("0");
        assertThat(result.limitAmount()).isEqualByComparingTo("1200000");
        assertThat(result.includedAmount()).isEqualByComparingTo("650000");
        assertThat(result.remainingAmount()).isEqualByComparingTo("550000");
        assertThat(result.resultStatus()).isEqualTo(CapResultStatus.NORMAL);
    }

    // CapCheckService.calculateAndSave()는 계산 결과를 cap_check/cap_check_detail에 저장한다
    @Test
    void calculateAndSavePersistsCapCheckAndDetails() {
        Long id = contractId("FGC-FGL01-202607-0001");
        insertOperationalScheduleWithOneBaseCommissionLine(id, LocalDate.of(2026, 7, 10));

        CapCheckSaveResult saved = capCheckService.calculateAndSave(
                CapCalculationCommand.realtime(id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        assertThat(saved.capCheckId()).isNotNull();
        assertThat(saved.result().limitAmount()).isEqualByComparingTo("1200000");

        Integer detailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.cap_check_detail WHERE cap_check_id = ?",
                Integer.class, saved.capCheckId());
        assertThat(detailCount).isEqualTo(1);
    }

    // #76 DailyChangedContractJob이 같은 날 FAILED→RUNNING으로 재시도할 때, 이미 성공 저장된
    // (validation_run_id, contract_id, payment_stage)를 다시 계산하려다 uq_cap_check_monthly
    // UNIQUE 위반으로 영구히 실패하던 문제의 회귀테스트(코드리뷰로 발견·수정, 2026-08-11).
    // insertCapCheck를 ON CONFLICT DO UPDATE로 바꿔서, 같은 실행 안에서 같은 계약·단계를
    // 두 번 계산해도 예외 없이 최신 값으로 덮어써야 한다.
    @Test
    void calculateAndSaveUpsertsWithinSameValidationRunInsteadOfViolatingUniqueConstraint() {
        Long id = contractId("FGC-FGL01-202607-0001");
        insertOperationalScheduleWithOneBaseCommissionLine(id, LocalDate.of(2026, 7, 10));

        Long validationRunId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES ('2026-07-01', 9001, 'MANUAL_CONTRACT')
                RETURNING validation_run_id
                """, Long.class);

        CapCalculationCommand command = CapCalculationCommand.dailyBatch(
                id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10), validationRunId);

        CapCheckSaveResult first = capCheckService.calculateAndSave(command);
        // 재시도 시나리오: 같은 (validationRunId, contractId, paymentStage)를 다시 계산한다.
        CapCheckSaveResult second = capCheckService.calculateAndSave(command);

        // 새 행이 아니라 같은 행을 덮어썼어야 한다(UNIQUE 위반 없이, cap_check_id 그대로 유지).
        assertThat(second.capCheckId()).isEqualTo(first.capCheckId());

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.cap_check WHERE validation_run_id = ? AND contract_id = ? AND payment_stage = 'GA_TO_FC'",
                Integer.class, validationRunId, id);
        assertThat(rowCount).isEqualTo(1);

        // 예전 detail이 남아있지 않고 이번 재계산 결과로 정확히 교체됐어야 한다.
        Integer detailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.cap_check_detail WHERE cap_check_id = ?",
                Integer.class, second.capCheckId());
        assertThat(detailCount).isEqualTo(1);
    }

    // CapCheckService.findLatest()는 저장된 판정을 재계산 없이 그대로 돌려준다
    @Test
    void findLatestReturnsPersistedCapCheckWithoutRecalculating() {
        Long id = contractId("FGC-FGL01-202607-0001");
        insertOperationalScheduleWithOneBaseCommissionLine(id, LocalDate.of(2026, 7, 10));

        CapCheckSaveResult saved = capCheckService.calculateAndSave(
                CapCalculationCommand.realtime(id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        CapCheckSaveResult found = capCheckService.findLatest(id, PaymentStage.GA_TO_FC).orElseThrow();

        assertThat(found.capCheckId()).isEqualTo(saved.capCheckId());
        assertThat(found.result().limitAmount()).isEqualByComparingTo("1200000");
        assertThat(found.result().includedAmount()).isEqualByComparingTo("650000");
        assertThat(found.result().details()).hasSize(1);
    }

    // IF-API-30(FUN-030-05): search/count/summarize SQL이 실제 join·필터 조건으로 동작하는지
    // 검증한다 — 지금까지는 mock 매퍼로만 테스트돼 있었다
    @Test
    void searchReturnsPersistedCapCheckFilteredByContractNoWithSummaryCounts() {
        Long id = contractId("FGC-FGL01-202607-0001");
        insertOperationalScheduleWithOneBaseCommissionLine(id, LocalDate.of(2026, 7, 10));

        CapCheckSaveResult saved = capCheckService.calculateAndSave(
                CapCalculationCommand.realtime(id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        CapCheckSearchCriteria criteria = new CapCheckSearchCriteria(
                LocalDate.of(2026, 7, 1), "GA_TO_FC", null, null, "FGC-FGL01-202607-0001");
        CapCheckSearchResult result = capCheckService.search(criteria, 1, 20);

        assertThat(result.page().content())
                .extracting(CapCheckListRow::getCapCheckId)
                .contains(saved.capCheckId());
        assertThat(result.page().totalElements()).isEqualTo(1);
        assertThat(result.summary().normal()).isEqualTo(1);
        assertThat(result.summary().warning() + result.summary().violation()
                + result.summary().reviewRequired()).isEqualTo(0);
    }

    // IF-API-31(FUN-035, api-spec.md): 저장된 계산 스냅샷을 재계산 없이 capCheck+details[]+
    // calculationSnapshot 구조로 그대로 펼쳐 돌려줘야 한다
    @Test
    void findDetailReturnsPersistedBasisAsNestedCapCheckAndDetails() {
        Long id = contractId("FGC-FGL01-202607-0001");
        insertOperationalScheduleWithOneBaseCommissionLine(id, LocalDate.of(2026, 7, 10));

        CapCheckSaveResult saved = capCheckService.calculateAndSave(
                CapCalculationCommand.realtime(id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        CapCheckBasisResponse basis = capCheckService.findDetail(saved.capCheckId()).orElseThrow();

        assertThat(basis.capCheck().capCheckId()).isEqualTo(saved.capCheckId());
        assertThat(basis.capCheck().contractNo()).isEqualTo("FGC-FGL01-202607-0001");
        assertThat(basis.capCheck().limitAmount()).isEqualTo(1_200_000L);
        assertThat(basis.capCheck().includedAmount()).isEqualTo(650_000L);
        assertThat(basis.details()).hasSize(1);
        assertThat(basis.details().get(0).classificationSnapshot()).isEqualTo("INCLUDED");
        long includedSum = basis.details().stream()
                .filter(d -> "INCLUDED".equals(d.classificationSnapshot()))
                .mapToLong(com.susukkang.fgc.cap.dto.CapCheckDetailResponse::amount)
                .sum();
        assertThat(includedSum).isEqualTo(basis.capCheck().includedAmount());
    }

    // 존재하지 않는 capCheckId는 매퍼까지 실제로 태워도 빈 결과를 돌려줘야 한다(컨트롤러에서 404로 매핑)
    @Test
    void findDetailReturnsEmptyForNonExistentCapCheckId() {
        assertThat(capCheckService.findDetail(-1L)).isEmpty();
    }

    // A1(80% 공제대상 상품)은 12차월 예상해약환급률표가 한도에 가산된다
    // FGC-FGL02-202601-0001 : STD-LIFE-B(80% 공제 대상), 월납 100,000원, 240개월납, 계약일 2026-01-15
    // GA_TO_FC 1,200% 룰셋은 2026-07-01부터 적용되므로(REG-CAP-GA-2026-V1), 이 계약은
    // INSURER_TO_GA 단계로 검증한다. 준법경영비 3% 공제(REG-10, 제4-32조제14항)는 2027.1.1부터

    // 시행이라 2026-01-15 계약에는 아직 적용되지 않는다(REG-CAP-INS-2021-V2, 공제 0%, V6_1).
    @Test
    void standardDeduction80ProductA1AddsMonth12RefundRateToLimit() {
        Long id = contractId("FGC-FGL02-202601-0001");

        CapCalculationResult result = capCalculator.calculate(
                CapCalculationCommand.realtime(id, PaymentStage.INSURER_TO_GA, LocalDate.of(2026, 1, 15)));

        // seed 공식: GREATEST(0,(12-2)*2.4) = 24.0% → 1,200,000 × 24% = 288,000
        assertThat(result.refund12mAmount()).isEqualByComparingTo("288000");
        // 준법경영비 3% 공제는 2027.1.1부터 시행이라 2026년 계약에는 적용되지 않는다 → gross 그대로 한도
        assertThat(result.complianceDeductionAmount()).isEqualByComparingTo("0");
        assertThat(result.limitAmount()).isEqualByComparingTo("1488000");
        assertThat(result.refundRateTableId()).isNotNull();
    }


    // REG-10(준법경영비 3% 공제)은 2027.1.1 이후 체결된 원수사→GA 계약부터 적용된다(REG-CAP-INS-2027-V1).
    // FGC-FGL01-202703-0001 : STD-LIFE-A(80% 공제 아님), 월납 100,000원, 계약일 2027-03-02
    // 공제 기준액은 grossLimit이 아니라 월납 원액이다(feature/18 반영) — 3% × 100,000 = 3,000.
    @Test
    void complianceDeductionAppliesFromContractsDatedOnOrAfter20270101() {
        Long id = contractId("FGC-FGL01-202703-0001");

        CapCalculationResult result = capCalculator.calculate(
                CapCalculationCommand.realtime(
                        id,
                        PaymentStage.INSURER_TO_GA,
                        LocalDate.of(2027, 3, 2),
                        new BigDecimal("3000")
                ));

        assertThat(result.refund12mAmount()).isEqualByComparingTo("0");
        assertThat(result.complianceDeductionAmount()).isEqualByComparingTo("3000");
        assertThat(result.limitAmount()).isEqualByComparingTo("1197000");
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
