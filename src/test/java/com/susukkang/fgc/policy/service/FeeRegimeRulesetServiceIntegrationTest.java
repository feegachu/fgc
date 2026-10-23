package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.policy.code.FeeRegimeReasonCode;
import com.susukkang.fgc.policy.code.FeeRegimeResolutionStatus;
import com.susukkang.fgc.policy.dto.FeeRegimeRulesetSelection;
import com.susukkang.fgc.policy.repository.FeeRegimeResolverRepository;
import com.susukkang.fgc.policy.repository.PolicyVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 분급 룰셋 조회·적용 서비스(#537)가 판정 결과와 룰셋 헤더·한도 행을 함께 돌려주는지 PostgreSQL 로 검증하는 통합 테스트
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FeeRegimeRulesetServiceIntegrationTest {

    @Autowired
    private PolicyVersionRepository policyVersionRepository;

    @Autowired
    private FeeRegimeResolverRepository resolverRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private FeeRegimeRulesetService service;

    @BeforeEach
    void setUp() {
        ProductFeeRegimeResolver resolver = new ProductFeeRegimeResolver(
                new FeeRegimeDecisionInputServiceImpl(policyVersionRepository), resolverRepository);
        service = new FeeRegimeRulesetService(resolver, resolverRepository);
        // 데모 시드(V65)의 규제 상한 룰셋과 겹치지 않게, 이 테스트가 만든 룰셋만 후보로 남긴다
        jdbc.update("UPDATE fgc.policy_version SET status = 'RETIRED' WHERE policy_code LIKE 'REG-%-V1' AND status = 'ACTIVE'");
    }

    private long g1ContractId() {
        return jdbc.queryForObject("SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, "FGC-FGL01-202703-0001");
    }

    // 시나리오 1: 4년 적용 계약 — SELECTED, 룰셋·한도 행·선택 근거(사용 키·REG-19)가 함께 반환된다
    @Test
    void fourYearContractReturnsRulesetLimitsAndSelectionBasis() {
        long contractId = g1ContractId();
        makeG1Offering("FOUR_YEAR_2027", "2027-01-01");
        long policyId = insertDraftPolicy("TEST-SVC-4Y", "FOUR_YEAR_COMMISSION");
        long rulesetId = insertRuleset(policyId, "FOUR_YEAR_2027", "2027-01-01", null, 48, null, null);
        insertLimit(rulesetId, "UPFRONT_TOTAL", "1.000000");
        insertLimit(rulesetId, "MAINTENANCE_MONTHLY", "1.500000");
        activate(policyId);

        FeeRegimeRulesetSelection selection = service.resolve(contractId);

        assertThat(selection.isSelected()).isTrue();
        assertThat(selection.resolution().reasonCode()).isEqualTo(FeeRegimeReasonCode.RULESET_SELECTED);
        assertThat(selection.resolution().regRefs()).contains("REG-19");
        assertThat(selection.resolution().contractDate()).isEqualTo(LocalDate.of(2027, 3, 2));
        assertThat(selection.resolution().salesStartDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(selection.resolution().basicDocumentVersion()).isEqualTo("BD-2026-01");
        assertThat(selection.ruleset().rulesetId()).isEqualTo(rulesetId);
        assertThat(selection.ruleset().policyVersionId()).isEqualTo(policyId);
        assertThat(selection.ruleset().feeRegimeCode()).isEqualTo("FOUR_YEAR_2027");
        assertThat(selection.ruleset().maxInstallments()).isEqualTo(48);
        assertThat(selection.ruleset().longTermMaintenanceFrom()).isNull();
        assertThat(selection.limits()).extracting(l -> l.limitCode())
                .containsExactly("MAINTENANCE_MONTHLY", "UPFRONT_TOTAL");
        assertThat(selection.limit("MAINTENANCE_MONTHLY").orElseThrow().limitPct())
                .isEqualByComparingTo(new BigDecimal("1.5"));
        assertThat(selection.limit("LONG_TERM_MONTHLY")).isEmpty();
    }

    // 시나리오 2: 7년 적용 계약 — 장기유지 구간과 장기유지 월 한도가 함께 반환된다
    @Test
    void sevenYearContractReturnsLongTermRangeAndLimit() {
        long contractId = g1ContractId();
        makeG1Offering("SEVEN_YEAR_2029", "2029-01-01");
        jdbc.update("UPDATE fgc.insurance_contract SET contract_date = DATE '2029-03-02' WHERE contract_id = ?",
                contractId);
        long policyId = insertDraftPolicy("TEST-SVC-7Y", "SEVEN_YEAR_COMMISSION");
        long rulesetId = insertRuleset(policyId, "SEVEN_YEAR_2029", "2029-01-01", null, 84, 61, 84);
        insertLimit(rulesetId, "UPFRONT_TOTAL", "1.000000");
        insertLimit(rulesetId, "MAINTENANCE_MONTHLY", "0.800000");
        insertLimit(rulesetId, "LONG_TERM_MONTHLY", "0.400000");
        activate(policyId);

        FeeRegimeRulesetSelection selection = service.resolve(contractId);

        assertThat(selection.isSelected()).isTrue();
        assertThat(selection.ruleset().maxInstallments()).isEqualTo(84);
        assertThat(selection.ruleset().longTermMaintenanceFrom()).isEqualTo(61);
        assertThat(selection.ruleset().longTermMaintenanceTo()).isEqualTo(84);
        assertThat(selection.limit("LONG_TERM_MONTHLY").orElseThrow().limitPct())
                .isEqualByComparingTo(new BigDecimal("0.4"));
    }

    // 시나리오 3·7(#534 결정에 따라 변경): 경과조치는 룰셋의 판매개시일 구간으로 노출한다.
    // 구간 상한(판매개시일 to) 당일은 포함하고, 하루 지나면 룰셋이 맞지 않아 RULESET_MISSING 이다.
    @Test
    void specialPeriodEndIsExposedAndIncludesBoundaryDay() {
        long contractId = g1ContractId();
        // 계약일이 두 판매개시일보다 늦어야 판매기간 판정(2번)에 걸리지 않는다
        jdbc.update("UPDATE fgc.insurance_contract SET contract_date = DATE '2027-07-15' WHERE contract_id = ?",
                contractId);
        long policyId = insertDraftPolicy("TEST-SVC-4Y-SPECIAL", "FOUR_YEAR_COMMISSION");
        long rulesetId = insertRuleset(policyId, "FOUR_YEAR_2027", "2027-01-01", "2027-06-30", 48, null, null);
        insertLimit(rulesetId, "UPFRONT_TOTAL", "1.000000");
        insertLimit(rulesetId, "MAINTENANCE_MONTHLY", "1.500000");
        activate(policyId);

        makeG1Offering("FOUR_YEAR_2027", "2027-06-30");
        FeeRegimeRulesetSelection onBoundary = service.resolve(contractId);
        assertThat(onBoundary.isSelected()).isTrue();
        assertThat(onBoundary.specialPeriodEnd()).contains(LocalDate.of(2027, 6, 30));

        makeG1Offering("FOUR_YEAR_2027", "2027-07-01");
        FeeRegimeRulesetSelection afterBoundary = service.resolve(contractId);
        assertThat(afterBoundary.isSelected()).isFalse();
        assertThat(afterBoundary.resolution().reasonCode()).isEqualTo(FeeRegimeReasonCode.RULESET_MISSING);
        assertThat(afterBoundary.specialPeriodEnd()).isEmpty();
    }

    // 리뷰 반영: 특례기간 종료일은 4년 체계 룰셋에서만 돌려준다 — 7년 룰셋의 구간 상한은 특례와 무관하다
    @Test
    void specialPeriodEndIsEmptyForSevenYearRulesetWithUpperBound() {
        long contractId = g1ContractId();
        makeG1Offering("SEVEN_YEAR_2029", "2029-01-01");
        jdbc.update("UPDATE fgc.insurance_contract SET contract_date = DATE '2029-03-02' WHERE contract_id = ?",
                contractId);
        long policyId = insertDraftPolicy("TEST-SVC-7Y-BOUND", "SEVEN_YEAR_COMMISSION");
        long rulesetId = insertRuleset(policyId, "SEVEN_YEAR_2029", "2029-01-01", "2029-12-31", 84, 61, 84);
        insertLimit(rulesetId, "UPFRONT_TOTAL", "1.000000");
        insertLimit(rulesetId, "MAINTENANCE_MONTHLY", "0.800000");
        insertLimit(rulesetId, "LONG_TERM_MONTHLY", "0.400000");
        activate(policyId);

        FeeRegimeRulesetSelection selection = service.resolve(contractId);

        assertThat(selection.isSelected()).isTrue();
        assertThat(selection.ruleset().salesStartDateTo()).isEqualTo(LocalDate.of(2029, 12, 31));
        assertThat(selection.specialPeriodEnd()).isEmpty();
    }

    // 리뷰 반영: 필수 한도 행이 없는 룰셋은 SELECTED 로 내보내지 않고 REVIEW_REQUIRED 로 돌려준다
    @Test
    void rulesetWithoutAnyLimitRowIsReviewRequired() {
        long contractId = g1ContractId();
        makeG1Offering("FOUR_YEAR_2027", "2027-01-01");
        long policyId = insertDraftPolicy("TEST-SVC-4Y-NOLIMIT", "FOUR_YEAR_COMMISSION");
        long rulesetId = insertRuleset(policyId, "FOUR_YEAR_2027", "2027-01-01", null, 48, null, null);
        activate(policyId);

        FeeRegimeRulesetSelection selection = service.resolve(contractId);

        assertThat(selection.isSelected()).isFalse();
        assertThat(selection.resolution().status()).isEqualTo(FeeRegimeResolutionStatus.REVIEW_REQUIRED);
        assertThat(selection.resolution().reasonCode()).isEqualTo(FeeRegimeReasonCode.RULESET_LIMIT_MISSING);
        assertThat(selection.resolution().candidateRulesetIds()).containsExactly(rulesetId);
        // 다른 REVIEW_REQUIRED 결과처럼 rulesetId·policyVersionId 는 SELECTED 일 때만 채운다
        assertThat(selection.resolution().rulesetId()).isNull();
        assertThat(selection.resolution().policyVersionId()).isNull();
        assertThat(selection.ruleset()).isNull();
        assertThat(selection.limits()).isEmpty();
    }

    @Test
    void sevenYearRulesetWithoutLongTermLimitIsReviewRequired() {
        long contractId = g1ContractId();
        makeG1Offering("SEVEN_YEAR_2029", "2029-01-01");
        jdbc.update("UPDATE fgc.insurance_contract SET contract_date = DATE '2029-03-02' WHERE contract_id = ?",
                contractId);
        long policyId = insertDraftPolicy("TEST-SVC-7Y-NOLT", "SEVEN_YEAR_COMMISSION");
        long rulesetId = insertRuleset(policyId, "SEVEN_YEAR_2029", "2029-01-01", null, 84, 61, 84);
        insertLimit(rulesetId, "UPFRONT_TOTAL", "1.000000");
        insertLimit(rulesetId, "MAINTENANCE_MONTHLY", "0.800000");
        activate(policyId);

        FeeRegimeRulesetSelection selection = service.resolve(contractId);

        assertThat(selection.resolution().reasonCode()).isEqualTo(FeeRegimeReasonCode.RULESET_LIMIT_MISSING);
        assertThat(selection.isSelected()).isFalse();
    }

    // 시나리오 4: 정책 잠금(ACTIVE) 뒤 다시 조회해도 같은 룰셋 ID·같은 값이다
    @Test
    void sameContractReturnsSameSelectionOnRepeatedLookup() {
        long contractId = g1ContractId();
        makeG1Offering("FOUR_YEAR_2027", "2027-01-01");
        long policyId = insertDraftPolicy("TEST-SVC-4Y-REPEAT", "FOUR_YEAR_COMMISSION");
        long rulesetId = insertRuleset(policyId, "FOUR_YEAR_2027", "2027-01-01", null, 48, null, null);
        insertLimit(rulesetId, "UPFRONT_TOTAL", "1.000000");
        insertLimit(rulesetId, "MAINTENANCE_MONTHLY", "1.500000");
        activate(policyId);

        FeeRegimeRulesetSelection first = service.resolve(contractId);
        FeeRegimeRulesetSelection second = service.resolve(contractId);

        assertThat(second).isEqualTo(first);
        assertThat(second.ruleset().rulesetId()).isEqualTo(rulesetId);
    }

    // 시나리오 5: 경계 계약 G2(계약일 < 판매개시일) — REVIEW_REQUIRED 와 사유가 그대로 반환되고 룰셋 값은 비어 있다
    @Test
    void reviewRequiredIsPassedThroughWithoutRulesetValues() {
        long g2 = jdbc.queryForObject("SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, "FGC-FGL02-202611-0001");

        FeeRegimeRulesetSelection selection = service.resolve(g2);

        assertThat(selection.resolution().status()).isEqualTo(FeeRegimeResolutionStatus.REVIEW_REQUIRED);
        assertThat(selection.resolution().reasonCode()).isEqualTo(FeeRegimeReasonCode.CONTRACT_OUTSIDE_SALES_PERIOD);
        assertThat(selection.isSelected()).isFalse();
        assertThat(selection.ruleset()).isNull();
        assertThat(selection.limits()).isEmpty();
    }

    // 시나리오 6: 현행 판매버전 계약 G1 — NOT_APPLICABLE, 룰셋 값 없음 (호출부는 기존 현행 경로를 탄다)
    @Test
    void notApplicableIsPassedThroughWithoutRulesetValues() {
        FeeRegimeRulesetSelection selection = service.resolve(g1ContractId());

        assertThat(selection.resolution().status()).isEqualTo(FeeRegimeResolutionStatus.NOT_APPLICABLE);
        assertThat(selection.resolution().declaredFeeRegimeCode()).isEqualTo("CURRENT");
        assertThat(selection.isSelected()).isFalse();
        assertThat(selection.ruleset()).isNull();
        assertThat(selection.limits()).isEmpty();
    }

    // G1 계약의 판매버전을 지정한 체계·판매개시일로 바꿔 룰셋 조회 단계까지 가게 한다
    private void makeG1Offering(String regime, String salesStart) {
        jdbc.update("""
                UPDATE fgc.product_offering
                   SET fee_regime_code = ?, sales_start_date = ?::date, basic_document_date = DATE '2027-01-01'
                 WHERE product_offering_id = (SELECT product_offering_id FROM fgc.insurance_contract WHERE contract_id = ?)
                """, regime, salesStart, g1ContractId());
    }

    private long insertDraftPolicy(String code, String policyType) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.policy_version (policy_code, policy_name, policy_type, source_class,
                    version_no, effective_from, status)
                VALUES (?, '분급 룰셋 서비스 테스트 정책', ?, 'PROJECT_ASSUMPTION', 1, DATE '2026-01-01', 'DRAFT')
                RETURNING policy_version_id
                """, Long.class, code, policyType);
    }

    private long insertRuleset(long policyId, String regime, String salesFrom, String salesTo, int maxInstallments,
                               Integer longTermFrom, Integer longTermTo) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.fee_regime_ruleset (policy_version_id, fee_regime_code, sales_start_date_from,
                    sales_start_date_to, basic_document_version, max_installments,
                    long_term_maintenance_from, long_term_maintenance_to)
                VALUES (?, ?, ?::date, ?::date, 'BD-2026-01', ?, ?, ?)
                RETURNING fee_regime_ruleset_id
                """, Long.class, policyId, regime, salesFrom, salesTo, maxInstallments, longTermFrom, longTermTo);
    }

    private void insertLimit(long rulesetId, String limitCode, String limitPct) {
        jdbc.update("""
                INSERT INTO fgc.fee_regime_ruleset_limit (fee_regime_ruleset_id, limit_code, limit_pct)
                VALUES (?, ?, ?::numeric)
                """, rulesetId, limitCode, limitPct);
    }

    private void activate(long policyId) {
        jdbc.update("""
                UPDATE fgc.policy_version
                   SET status = 'APPROVED', approved_at = clock_timestamp(),
                       created_by = (SELECT min(user_id) FROM fgc.app_user),
                       approved_by = (SELECT max(user_id) FROM fgc.app_user)
                 WHERE policy_version_id = ?
                """, policyId);
        jdbc.update("UPDATE fgc.policy_version SET status = 'ACTIVE' WHERE policy_version_id = ?", policyId);
    }
}
