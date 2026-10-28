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
 * 설명 : FUN-014 경계 샘플 세트(FR1~FR12)와 4년·7년 규제 상한 데모 시드(V65)를 PostgreSQL 로 검증하는 통합 테스트 (#538).
 * 샘플은 테스트 픽스처이므로 db/demo 에 넣지 않고, 시드 G1 계약과 그 판매버전 값을 이 테스트 안에서만 바꿔 만든다(롤백).
 * 판정표 자체는 FeeRegimeDecisionTableTest, 룰셋 조회는 ProductFeeRegimeResolverIntegrationTest 가 검증한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FeeRegimeBoundarySampleIntegrationTest {

    private static final String FOUR = "FOUR_YEAR_2027";
    private static final String SEVEN = "SEVEN_YEAR_2029";
    private static final String CURRENT = "CURRENT";

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
    }

    private long g1ContractId() {
        return jdbc.queryForObject("SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, "FGC-FGL01-202703-0001");
    }

    // G1 계약과 그 판매버전을 표의 행대로 바꿔 판정한다. 값을 정하는 곳은 이 한 곳뿐이다.
    private FeeRegimeRulesetSelection judge(String contractDate, String salesStart, String documentVersion,
                                            String documentDate, String regime) {
        long contractId = g1ContractId();
        jdbc.update("UPDATE fgc.insurance_contract SET contract_date = ?::date WHERE contract_id = ?",
                contractDate, contractId);
        jdbc.update("""
                UPDATE fgc.product_offering
                   SET fee_regime_code = ?, sales_start_date = ?::date,
                       basic_document_version = ?, basic_document_date = ?::date
                 WHERE product_offering_id = (SELECT product_offering_id FROM fgc.insurance_contract WHERE contract_id = ?)
                """, regime, salesStart, documentVersion, documentDate, contractId);
        return service.resolve(contractId);
    }

    private FeeRegimeRulesetSelection fr1() {
        return judge("2027-03-02", "2027-01-01", "BD-2027-01", "2026-12-01", FOUR);
    }

    private void assertReview(FeeRegimeRulesetSelection selection, FeeRegimeReasonCode reason) {
        assertThat(selection.resolution().status()).isEqualTo(FeeRegimeResolutionStatus.REVIEW_REQUIRED);
        assertThat(selection.resolution().reasonCode()).isEqualTo(reason);
        // 경계사례는 확정 룰셋으로 반환되지 않는다
        assertThat(selection.isSelected()).isFalse();
        assertThat(selection.resolution().rulesetId()).isNull();
        assertThat(selection.ruleset()).isNull();
        assertThat(selection.limits()).isEmpty();
    }

    // 시드: 4년·7년 규제 상한 룰셋과 한도 행 (운영정책서 제46·47조)
    @Test
    void seededRulesetsCarryRegulatoryLimits() {
        FeeRegimeRulesetSelection four = fr1();
        assertThat(four.isSelected()).isTrue();
        assertThat(four.ruleset().feeRegimeCode()).isEqualTo(FOUR);
        assertThat(four.ruleset().maxInstallments()).isEqualTo(48);
        assertThat(four.ruleset().longTermMaintenanceFrom()).isNull();
        assertThat(four.limit("UPFRONT_TOTAL").orElseThrow().limitPct()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(four.limit("MAINTENANCE_MONTHLY").orElseThrow().limitPct()).isEqualByComparingTo(new BigDecimal("1.5"));
        assertThat(four.limit("MAINTENANCE_TOTAL").orElseThrow().limitPct()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(four.limit("LONG_TERM_MONTHLY")).isEmpty();
        assertThat(four.specialPeriodEnd()).contains(LocalDate.of(2028, 12, 31));

        String sourceClass = jdbc.queryForObject("""
                SELECT source_class FROM fgc.policy_version WHERE policy_version_id = ?
                """, String.class, four.ruleset().policyVersionId());
        assertThat(sourceClass).isEqualTo("REGULATORY");
    }

    // 시나리오 3: FR7 — 7년 시드 룰셋에서 최대 84회·장기유지 61~84차월이 데이터로 반환된다
    @Test
    void fr7SelectsSevenYearRulesetWithLongTermRange() {
        FeeRegimeRulesetSelection seven = judge("2029-03-02", "2029-01-01", "BD-2029-01", "2028-12-01", SEVEN);

        assertThat(seven.isSelected()).isTrue();
        assertThat(seven.ruleset().feeRegimeCode()).isEqualTo(SEVEN);
        assertThat(seven.ruleset().maxInstallments()).isEqualTo(84);
        assertThat(seven.ruleset().longTermMaintenanceFrom()).isEqualTo(61);
        assertThat(seven.ruleset().longTermMaintenanceTo()).isEqualTo(84);
        assertThat(seven.limit("MAINTENANCE_MONTHLY").orElseThrow().limitPct()).isEqualByComparingTo(new BigDecimal("0.8"));
        assertThat(seven.limit("LONG_TERM_MONTHLY").orElseThrow().limitPct()).isEqualByComparingTo(new BigDecimal("0.4"));
        assertThat(seven.specialPeriodEnd()).isEmpty();
    }

    // 시나리오 1: 계약일이 같아도 판매개시일이 다르면 서로 다른 적용체계 — FR1 대 FR2·FR4
    @Test
    void sameContractDateWithDifferentSalesStartSelectsDifferentRegime() {
        FeeRegimeRulesetSelection fr1 = fr1();
        assertThat(fr1.isSelected()).isTrue();
        assertThat(fr1.ruleset().feeRegimeCode()).isEqualTo(FOUR);

        FeeRegimeRulesetSelection fr2 = judge("2027-03-02", "2026-01-01", "BD-2026-01", "2026-01-01", CURRENT);
        assertThat(fr2.resolution().status()).isEqualTo(FeeRegimeResolutionStatus.NOT_APPLICABLE);
        assertThat(fr2.isSelected()).isFalse();

        FeeRegimeRulesetSelection fr4 = judge("2027-03-02", "2026-12-31", "BD-2026-12", "2026-12-01", FOUR);
        assertReview(fr4, FeeRegimeReasonCode.REGIME_NOT_ELIGIBLE);
    }

    // 시나리오 2: 계약일·판매개시일이 같아도 기초서류가 다르면 서로 다른 적용체계 — FR1 대 FR3
    @Test
    void sameContractDateAndSalesStartWithDifferentDocumentSelectsDifferentRegime() {
        assertThat(fr1().isSelected()).isTrue();

        FeeRegimeRulesetSelection fr3 = judge("2027-03-02", "2027-01-01", "BD-2026-02", "2026-02-01", FOUR);
        assertReview(fr3, FeeRegimeReasonCode.REGIME_NOT_ELIGIBLE);
    }

    // 시나리오 4: FR10 — 판매채널만 다른 입력도 FR1과 같은 결과이고 특례 룰셋은 없다(SRC-036)
    @Test
    void fr10ChannelOnlyDifferenceGivesSameResultAsFr1() {
        FeeRegimeRulesetSelection fr1 = fr1();

        jdbc.update("""
                UPDATE fgc.product_offering SET channel_code = 'OTHER_CHANNEL'
                 WHERE product_offering_id = (SELECT product_offering_id FROM fgc.insurance_contract WHERE contract_id = ?)
                """, g1ContractId());
        FeeRegimeRulesetSelection fr10 = service.resolve(g1ContractId());

        assertThat(fr10).isEqualTo(fr1);
    }

    // 시나리오 5: FR3~FR6·FR8·FR11 — 모두 REVIEW_REQUIRED, 확정 룰셋으로 반환되지 않는다(인수조건 2)
    @Test
    void boundaryCasesAreNeverAutoConfirmed() {
        assertReview(judge("2027-03-02", "2027-01-01", "BD-2026-02", "2026-02-01", FOUR),
                FeeRegimeReasonCode.REGIME_NOT_ELIGIBLE);                                                  // FR3
        assertReview(judge("2027-03-02", "2026-12-31", "BD-2026-12", "2026-12-01", FOUR),
                FeeRegimeReasonCode.REGIME_NOT_ELIGIBLE);                                                  // FR4
        assertReview(judge("2026-11-10", "2027-01-01", "BD-2027-01", "2027-01-01", FOUR),
                FeeRegimeReasonCode.CONTRACT_OUTSIDE_SALES_PERIOD);                                        // FR5
        assertReview(judge("2029-03-02", "2027-01-01", "BD-2027-01", "2026-12-01", FOUR),
                FeeRegimeReasonCode.FOUR_YEAR_AFTER_SPECIAL_PERIOD);                                       // FR6
        assertReview(judge("2028-03-02", "2028-01-01", "BD-2028-01", "2027-12-01", SEVEN),
                FeeRegimeReasonCode.SEVEN_YEAR_IN_SPECIAL_PERIOD);                                         // FR8
        assertReview(judge("2027-03-02", "2027-01-01", "BD-2027-01", "2026-12-01", CURRENT),
                FeeRegimeReasonCode.CURRENT_BUT_ELIGIBLE);                                                 // FR11
    }

    // 시나리오 6: FR2(=G1)는 분급 미적용, FR9는 시행일 당일 작성도 적용례 충족으로 SELECTED — 확정된 경계는 REVIEW로 보내지 않는다
    @Test
    void confirmedBoundariesAreNotSentToReview() {
        FeeRegimeRulesetSelection g1 = service.resolve(g1ContractId());
        assertThat(g1.resolution().status()).isEqualTo(FeeRegimeResolutionStatus.NOT_APPLICABLE);
        assertThat(g1.resolution().declaredFeeRegimeCode()).isEqualTo(CURRENT);

        FeeRegimeRulesetSelection fr9 = judge("2027-03-02", "2027-01-01", "BD-2027-01", "2026-03-01", FOUR);
        assertThat(fr9.isSelected()).isTrue();
        assertThat(fr9.ruleset().feeRegimeCode()).isEqualTo(FOUR);
    }

    // 시드 G2(계약일 2026-11-10, 판매버전 2027-FOUR-YEAR)는 데이터를 고치지 않고 검토필요로 남는다
    @Test
    void seedG2StaysReviewRequired() {
        long g2 = jdbc.queryForObject("SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, "FGC-FGL02-202611-0001");

        assertReview(service.resolve(g2), FeeRegimeReasonCode.CONTRACT_OUTSIDE_SALES_PERIOD);
    }

    // 시나리오 8: FR12 — 종료일 없는 옛 현행 판매버전에 연결된 계약은 현행 체계로 계산하지 않는다
    @Test
    void fr12OldOpenEndedOfferingIsNotUnique() {
        long contractId = g1ContractId();
        jdbc.execute("SET CONSTRAINTS fgc.ex_product_offering_sales_period DEFERRED");
        jdbc.update("UPDATE fgc.insurance_contract SET contract_date = DATE '2027-03-02' WHERE contract_id = ?", contractId);
        jdbc.update("""
                UPDATE fgc.product_offering
                   SET sales_start_date = DATE '2026-07-01', sales_end_date = NULL,
                       basic_document_version = 'BD-2026-07', basic_document_date = DATE '2026-07-01'
                 WHERE product_offering_id = (SELECT product_offering_id FROM fgc.insurance_contract WHERE contract_id = ?)
                """, contractId);
        jdbc.update("""
                INSERT INTO fgc.product_offering (product_id, offering_version, sales_start_date, sales_end_date,
                    basic_document_version, basic_document_date, channel_code, fee_regime_code,
                    standard_deduction_80_yn, governance_status, committee_approval_date)
                SELECT po.product_id, '2027-FOUR-FR12', DATE '2027-01-01', NULL, 'BD-2027-01', DATE '2027-01-01',
                       po.channel_code, 'FOUR_YEAR_2027', true, 'APPROVED', DATE '2027-01-01'
                  FROM fgc.insurance_contract c
                  JOIN fgc.product_offering po ON po.product_offering_id = c.product_offering_id
                 WHERE c.contract_id = ?
                """, contractId);

        FeeRegimeRulesetSelection selection = service.resolve(contractId);

        assertReview(selection, FeeRegimeReasonCode.OFFERING_NOT_UNIQUE);
        assertThat(selection.resolution().candidateOfferingIds()).hasSize(2);
    }

    // 시나리오 8(뒤): 시드 정리(V60)로 닫힌 2026-CURRENT-B 에 2027-03-02 계약이 연결돼도 현행 체계로 계산하지 않는다
    @Test
    void contractAfterClosedCurrentBSalesPeriodIsOutsideSalesPeriod() {
        long contractId = g1ContractId();
        jdbc.update("""
                UPDATE fgc.insurance_contract
                   SET product_offering_id = (SELECT po.product_offering_id FROM fgc.product_offering po
                                                JOIN fgc.product p ON p.product_id = po.product_id
                                               WHERE p.standard_product_code = 'STD-LIFE-B'
                                                 AND po.offering_version = '2026-CURRENT-B'),
                       contract_date = DATE '2027-03-02'
                 WHERE contract_id = ?
                """, contractId);

        assertReview(service.resolve(contractId), FeeRegimeReasonCode.CONTRACT_OUTSIDE_SALES_PERIOD);
    }
}
