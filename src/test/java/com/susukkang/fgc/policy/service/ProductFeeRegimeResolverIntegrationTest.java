package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.policy.code.FeeRegimeReasonCode;
import com.susukkang.fgc.policy.code.FeeRegimeResolutionStatus;
import com.susukkang.fgc.policy.dto.FeeRegimeResolution;
import com.susukkang.fgc.policy.repository.FeeRegimeResolverRepository;
import com.susukkang.fgc.policy.repository.PolicyVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : ProductFeeRegimeResolver(#536)의 판매버전 후보·룰셋 후보 조회를 시드 계약과 PostgreSQL로 검증하는 통합 테스트.
 * 판정표 자체는 FeeRegimeDecisionTableTest 가 DB 없이 검증한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductFeeRegimeResolverIntegrationTest {

    @Autowired
    private PolicyVersionRepository policyVersionRepository;

    @Autowired
    private FeeRegimeResolverRepository resolverRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ProductFeeRegimeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ProductFeeRegimeResolver(
                new FeeRegimeDecisionInputServiceImpl(policyVersionRepository), resolverRepository);
    }

    private long contractId(String contractNo) {
        return jdbc.queryForObject("SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, contractNo);
    }

    private long g1ContractId() {
        return contractId("FGC-FGL01-202703-0001");
    }

    // 시나리오 9: 시드 G1(현행 판매버전 2026-CURRENT-A, 계약일 2027-03-02)은 분급 미적용 — 호출부가 기존 현행 경로를 탄다
    @Test
    void seedG1IsNotApplicable() {
        FeeRegimeResolution resolution = resolver.resolve(g1ContractId());

        assertThat(resolution.status()).isEqualTo(FeeRegimeResolutionStatus.NOT_APPLICABLE);
        assertThat(resolution.reasonCode()).isEqualTo(FeeRegimeReasonCode.NOT_APPLICABLE);
        assertThat(resolution.declaredFeeRegimeCode()).isEqualTo("CURRENT");
        assertThat(resolution.rulesetId()).isNull();
        assertThat(resolution.regRefs()).contains("REG-19");
    }

    // 시드 G2: 계약일(2026-11-10)이 판매개시일(2027-01-01)보다 이르다 — 데이터를 고치지 않고 검토필요
    @Test
    void seedG2IsOutsideSalesPeriod() {
        FeeRegimeResolution resolution = resolver.resolve(contractId("FGC-FGL02-202611-0001"));

        assertThat(resolution.status()).isEqualTo(FeeRegimeResolutionStatus.REVIEW_REQUIRED);
        assertThat(resolution.reasonCode()).isEqualTo(FeeRegimeReasonCode.CONTRACT_OUTSIDE_SALES_PERIOD);
    }

    // 시나리오 10: 종료일 없는 옛 판매버전에 연결된 계약과 같은 상품의 2027-01-01 판매개시 판매버전이 함께 있으면 후보 ID를 남기고 검토필요
    @Test
    void openEndedOldOfferingWithNewerOfferingIsNotUnique() {
        long contractId = g1ContractId();
        // 이 테스트는 같은 상품에 판매기간이 겹치는 판매버전을 일부러 만든다(롤백되므로 지연 검사는 실행되지 않는다)
        jdbc.execute("SET CONSTRAINTS fgc.ex_product_offering_sales_period DEFERRED");
        long newerId = insertFourYearOfferingForG1Product("2027-FOUR-OVERLAP");

        FeeRegimeResolution resolution = resolver.resolve(contractId);

        assertThat(resolution.status()).isEqualTo(FeeRegimeResolutionStatus.REVIEW_REQUIRED);
        assertThat(resolution.reasonCode()).isEqualTo(FeeRegimeReasonCode.OFFERING_NOT_UNIQUE);
        assertThat(resolution.candidateOfferingIds()).hasSize(2).contains(newerId);
    }

    // 시나리오 1·3·6: 룰셋 후보 0개 / 1개 / 경계사례 표시
    @Test
    void selectsSingleRulesetAndRespectsMissingAndFlagged() {
        long contractId = g1ContractId();
        makeG1OfferingFourYear();

        // 후보 0개 → 임의 기본값 없이 RULESET_MISSING
        FeeRegimeResolution missing = resolver.resolve(contractId);
        assertThat(missing.status()).isEqualTo(FeeRegimeResolutionStatus.REVIEW_REQUIRED);
        assertThat(missing.reasonCode()).isEqualTo(FeeRegimeReasonCode.RULESET_MISSING);

        // 후보 1개 → SELECTED, 같은 입력을 두 번 판정하면 같은 결과
        long policyId = insertDraftFourYearPolicy("TEST-RES-4Y");
        long rulesetId = insertRuleset(policyId, "BD-2026-01", false);
        activate(policyId);
        FeeRegimeResolution selected = resolver.resolve(contractId);
        assertThat(selected.status()).isEqualTo(FeeRegimeResolutionStatus.SELECTED);
        assertThat(selected.reasonCode()).isEqualTo(FeeRegimeReasonCode.RULESET_SELECTED);
        assertThat(selected.rulesetId()).isEqualTo(rulesetId);
        assertThat(selected.policyVersionId()).isEqualTo(policyId);
        assertThat(resolver.resolve(contractId)).isEqualTo(selected);

        // review_required_yn=true 인 룰셋은 자동 확정하지 않는다
        jdbc.update("UPDATE fgc.policy_version SET status = 'RETIRED' WHERE policy_version_id = ?", policyId);
        long flaggedPolicyId = insertDraftFourYearPolicy("TEST-RES-4Y-FLAG");
        long flaggedRulesetId = insertRuleset(flaggedPolicyId, "BD-2026-01", true);
        activate(flaggedPolicyId);
        FeeRegimeResolution flagged = resolver.resolve(contractId);
        assertThat(flagged.status()).isEqualTo(FeeRegimeResolutionStatus.REVIEW_REQUIRED);
        assertThat(flagged.reasonCode()).isEqualTo(FeeRegimeReasonCode.RULESET_FLAGGED);
        assertThat(flagged.candidateRulesetIds()).containsExactly(flaggedRulesetId);
    }

    // 시나리오 6: 정책버전 두 개 아래 룰셋이 둘 이상 맞으면 하나를 고르지 않고 RULESET_AMBIGUOUS
    @Test
    void rulesetsFromTwoActivePoliciesAreAmbiguous() {
        long contractId = g1ContractId();
        makeG1OfferingFourYear();
        long firstPolicy = insertDraftFourYearPolicy("TEST-RES-4Y-A");
        long first = insertRuleset(firstPolicy, "BD-2026-01", false);
        long secondPolicy = insertDraftFourYearPolicy("TEST-RES-4Y-B");
        long second = insertRuleset(secondPolicy, "BD-2026-01", false);
        activate(firstPolicy);
        activate(secondPolicy);

        FeeRegimeResolution resolution = resolver.resolve(contractId);

        assertThat(resolution.status()).isEqualTo(FeeRegimeResolutionStatus.REVIEW_REQUIRED);
        assertThat(resolution.reasonCode()).isEqualTo(FeeRegimeReasonCode.RULESET_AMBIGUOUS);
        assertThat(resolution.candidateRulesetIds()).containsExactlyInAnyOrder(first, second);
        assertThat(resolution.rulesetId()).isNull();
    }

    // #534 선택 키: 같은 정책버전 안에서는 기초서류 버전이 지정된 룰셋이 NULL(모든 버전) 룰셋보다 우선한다
    @Test
    void documentSpecificRulesetBeatsAllVersionRulesetWithinSamePolicyVersion() {
        long contractId = g1ContractId();
        makeG1OfferingFourYear();
        long policyId = insertDraftFourYearPolicy("TEST-RES-4Y-SAME");
        insertRuleset(policyId, null, false);
        long specific = insertRuleset(policyId, "BD-2026-01", false);
        activate(policyId);

        FeeRegimeResolution resolution = resolver.resolve(contractId);

        assertThat(resolution.status()).isEqualTo(FeeRegimeResolutionStatus.SELECTED);
        assertThat(resolution.rulesetId()).isEqualTo(specific);
    }

    // 정책버전이 서로 다르면 우선 규칙으로 후보를 가리지 않는다 — 정책 중복이 RULESET_AMBIGUOUS 로 드러난다
    @Test
    void rulesetsFromDifferentPolicyVersionsAreAmbiguousEvenIfOneIsDocumentSpecific() {
        long contractId = g1ContractId();
        makeG1OfferingFourYear();
        long allVersionsPolicy = insertDraftFourYearPolicy("TEST-RES-4Y-NULL");
        long allVersions = insertRuleset(allVersionsPolicy, null, false);
        long specificPolicy = insertDraftFourYearPolicy("TEST-RES-4Y-SPECIFIC");
        long specific = insertRuleset(specificPolicy, "BD-2026-01", false);
        activate(allVersionsPolicy);
        activate(specificPolicy);

        FeeRegimeResolution resolution = resolver.resolve(contractId);

        assertThat(resolution.status()).isEqualTo(FeeRegimeResolutionStatus.REVIEW_REQUIRED);
        assertThat(resolution.reasonCode()).isEqualTo(FeeRegimeReasonCode.RULESET_AMBIGUOUS);
        assertThat(resolution.candidateRulesetIds()).containsExactlyInAnyOrder(allVersions, specific);
    }

    // 정책버전이 다른 기초서류 버전(BD-OTHER) 전용이면 그 아래 NULL 룰셋도 이 계약(BD-2026-01)에 걸리지 않는다
    @Test
    void policyVersionForOtherDocumentVersionIsNotSelected() {
        long contractId = g1ContractId();
        makeG1OfferingFourYear();
        long policyId = insertDraftFourYearPolicy("TEST-RES-4Y-BD-OTHER", "BD-OTHER");
        insertRuleset(policyId, null, false);
        activate(policyId);

        FeeRegimeResolution resolution = resolver.resolve(contractId);

        assertThat(resolution.status()).isEqualTo(FeeRegimeResolutionStatus.REVIEW_REQUIRED);
        assertThat(resolution.reasonCode()).isEqualTo(FeeRegimeReasonCode.RULESET_MISSING);
    }

    // G1 계약의 판매버전을 "2027-01-01 판매개시, 4년 체계 선언"으로 바꿔 룰셋 조회 단계까지 가게 한다
    private void makeG1OfferingFourYear() {
        jdbc.update("""
                UPDATE fgc.product_offering
                   SET fee_regime_code = 'FOUR_YEAR_2027', sales_start_date = DATE '2027-01-01',
                       basic_document_date = DATE '2027-01-01'
                 WHERE product_offering_id = (SELECT product_offering_id FROM fgc.insurance_contract WHERE contract_id = ?)
                """, g1ContractId());
    }

    private long insertFourYearOfferingForG1Product(String version) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.product_offering (product_id, offering_version, sales_start_date, sales_end_date,
                    basic_document_version, basic_document_date, channel_code, fee_regime_code,
                    standard_deduction_80_yn, governance_status, committee_approval_date)
                SELECT po.product_id, ?, DATE '2027-01-01', NULL, 'BD-2027-01', DATE '2027-01-01',
                       po.channel_code, 'FOUR_YEAR_2027', true, 'APPROVED', DATE '2027-01-01'
                  FROM fgc.insurance_contract c
                  JOIN fgc.product_offering po ON po.product_offering_id = c.product_offering_id
                 WHERE c.contract_id = ?
                RETURNING product_offering_id
                """, Long.class, version, g1ContractId());
    }

    private long insertDraftFourYearPolicy(String code) {
        return insertDraftFourYearPolicy(code, null);
    }

    private long insertDraftFourYearPolicy(String code, String documentVersion) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.policy_version (policy_code, policy_name, policy_type, source_class,
                    version_no, effective_from, status, basic_document_version)
                VALUES (?, '분급 판정 테스트 4년 정책', 'FOUR_YEAR_COMMISSION', 'PROJECT_ASSUMPTION',
                        1, DATE '2026-01-01', 'DRAFT', ?)
                RETURNING policy_version_id
                """, Long.class, code, documentVersion);
    }

    private long insertRuleset(long policyId, String documentVersion, boolean reviewRequired) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.fee_regime_ruleset (policy_version_id, fee_regime_code, sales_start_date_from,
                    basic_document_version, max_installments, review_required_yn)
                VALUES (?, 'FOUR_YEAR_2027', DATE '2027-01-01', ?, 48, ?)
                RETURNING fee_regime_ruleset_id
                """, Long.class, policyId, documentVersion, reviewRequired);
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
