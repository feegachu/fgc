package com.susukkang.fgc.policy.repository;

import com.susukkang.fgc.common.code.AgentRankCode;
import com.susukkang.fgc.common.code.CalculationType;
import com.susukkang.fgc.common.code.FeeComponentType;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.policy.dto.ResolvedCommissionPolicy;
import com.susukkang.fgc.policy.dto.ResolvedCommissionRule;
import com.susukkang.fgc.schedule.code.ScheduleRegime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CommissionPolicyRepositoryIntegrationTest {

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 8, 11);
    private static final PaymentStage STAGE = PaymentStage.INSURER_TO_GA;
    private static final Scope ALL_SCOPES = new Scope(null, null, null);

    @Autowired
    private PolicyVersionRepository policyVersionRepository;

    @Autowired
    private CommissionRuleRepository commissionRuleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Set<Long> testPolicyIds = new HashSet<>();
    private Long writerId;
    private Long approverId;

    @Test
    void selectsAllApplicableActivePoliciesInOriginalOrderWithoutDuplicatingMatchingRules() {
        Fixture fixture = insertFixture();
        Scope matching = fixture.scope();
        long older = activePolicy(CONTRACT_DATE.minusDays(1), null, 99, null, null, STAGE, matching);
        long versionOne = activePolicy(CONTRACT_DATE, CONTRACT_DATE, 1,
                "CURRENT", fixture.documentVersion(), STAGE, matching);
        long versionTwo = activePolicy(CONTRACT_DATE, CONTRACT_DATE, 2,
                "CURRENT", fixture.documentVersion(), STAGE, matching);
        long laterId = draftPolicy(CONTRACT_DATE, CONTRACT_DATE, 2, "CURRENT", fixture.documentVersion());
        insertRule(laterId, STAGE, matching);
        insertRule(laterId, STAGE, ALL_SCOPES);
        activate(laterId);

        long draft = draftPolicy(CONTRACT_DATE, null, 1, "CURRENT", fixture.documentVersion());
        insertRule(draft, STAGE, matching);
        activePolicy(CONTRACT_DATE.plusDays(1), null, 1, "CURRENT", fixture.documentVersion(), STAGE, matching);
        activePolicy(CONTRACT_DATE.minusDays(2), CONTRACT_DATE.minusDays(1), 1,
                "CURRENT", fixture.documentVersion(), STAGE, matching);
        activePolicy(CONTRACT_DATE, null, 1, "FOUR_YEAR_2027", fixture.documentVersion(), STAGE, matching);
        activePolicy(CONTRACT_DATE, null, 1, "CURRENT", "OTHER-DOCUMENT", STAGE, matching);
        activePolicy(CONTRACT_DATE, null, 1, "CURRENT", fixture.documentVersion(), PaymentStage.GA_TO_FC, matching);
        activePolicy(CONTRACT_DATE, null, 1, "CURRENT", fixture.documentVersion(), STAGE,
                new Scope(insertInsurer(), fixture.offeringId(), fixture.organizationId()));
        activePolicy(CONTRACT_DATE, null, 1, "CURRENT", fixture.documentVersion(), STAGE,
                new Scope(fixture.insurerId(), insertOffering(fixture.productId(), fixture.documentVersion()), fixture.organizationId()));
        activePolicy(CONTRACT_DATE, null, 1, "CURRENT", fixture.documentVersion(), STAGE,
                new Scope(fixture.insurerId(), fixture.offeringId(), insertOrganization()));
        long withoutRules = draftPolicy(CONTRACT_DATE, null, 1, "CURRENT", fixture.documentVersion());
        activate(withoutRules);
        long otherType = draftPolicy(CONTRACT_DATE, null, 1, "CURRENT", fixture.documentVersion());
        jdbcTemplate.update("UPDATE fgc.policy_version SET policy_type = 'REFUND_RATE' WHERE policy_version_id = ?", otherType);
        insertRule(otherType, STAGE, matching);
        activate(otherType);

        List<ResolvedCommissionPolicy> result = policiesFor(fixture.contractId(), STAGE);

        assertThat(result).extracting(ResolvedCommissionPolicy::getPolicyVersionId)
                .containsExactly(laterId, versionTwo, versionOne, older);
        assertThat(result).allSatisfy(policy -> {
            assertThat(policy.getPolicyType()).isEqualTo(PolicyType.CURRENT_COMMISSION);
            assertThat(policy.getPaymentStage()).isEqualTo(STAGE);
            assertThat(policy.getScheduleRegime()).isEqualTo(ScheduleRegime.CURRENT);
        });
        assertThat(policiesFor(fixture.contractId(), PaymentStage.GA_TO_FC)).hasSize(1);
        assertThat(policyVersionRepository.findApplicableCurrentCommissionPolicies(Long.MAX_VALUE, STAGE)).isEmpty();
    }

    @Test
    void requiresActiveOfferingAndInclusiveSalesDates() {
        Fixture fixture = insertFixture();
        long policyId = activePolicy(CONTRACT_DATE.minusDays(1), null, 1,
                "CURRENT", fixture.documentVersion(), STAGE, fixture.scope());

        // 판매 시작일과 종료일이 계약일과 같아도 적용된다.
        assertThat(policiesFor(fixture.contractId(), STAGE)).extracting(ResolvedCommissionPolicy::getPolicyVersionId)
                .containsExactly(policyId);
        jdbcTemplate.update("UPDATE fgc.product_offering SET active_yn = false WHERE product_offering_id = ?",
                fixture.offeringId());
        assertThat(policiesFor(fixture.contractId(), STAGE)).isEmpty();
        jdbcTemplate.update("""
                UPDATE fgc.product_offering SET active_yn = true, sales_start_date = ?, sales_end_date = NULL
                WHERE product_offering_id = ?
                """, CONTRACT_DATE.plusDays(1), fixture.offeringId());
        assertThat(policiesFor(fixture.contractId(), STAGE)).isEmpty();
        jdbcTemplate.update("""
                UPDATE fgc.product_offering SET sales_start_date = ?, sales_end_date = ?
                WHERE product_offering_id = ?
                """, CONTRACT_DATE.minusDays(2), CONTRACT_DATE.minusDays(1), fixture.offeringId());
        assertThat(policiesFor(fixture.contractId(), STAGE)).isEmpty();
        jdbcTemplate.update("UPDATE fgc.product_offering SET sales_end_date = NULL WHERE product_offering_id = ?",
                fixture.offeringId());
        assertThat(policiesFor(fixture.contractId(), STAGE)).extracting(ResolvedCommissionPolicy::getPolicyVersionId)
                .containsExactly(policyId);
    }

    @Test
    void resolvesMatchingRulesWithEnumNullDecimalMappingsAndPriorityInstallmentIdOrder() {
        Fixture fixture = insertFixture();
        long policyId = draftPolicy(CONTRACT_DATE, null, 1, "CURRENT", fixture.documentVersion());
        long generic = insertRule(policyId, STAGE, ALL_SCOPES, null, 1, 30,
                new BigDecimal("1.234567"), null);
        long laterInstallment = insertRule(policyId, STAGE, fixture.scope(), "FC", 2, 10,
                new BigDecimal("123.456789"), null);
        long firstTie = insertRule(policyId, STAGE, fixture.scope(), "TEAM_LEADER", 1, 10,
                new BigDecimal("3.000000"), null);
        long secondTie = insertRule(policyId, STAGE, fixture.scope(), "BRANCH_MANAGER", 1, 10,
                new BigDecimal("2.000000"), null);
        long fixed = insertRule(policyId, STAGE, ALL_SCOPES, null, 1, 20,
                null, new BigDecimal("1234.50"));
        insertRule(policyId, PaymentStage.GA_TO_FC, fixture.scope());
        insertRule(policyId, STAGE, new Scope(insertInsurer(), fixture.offeringId(), fixture.organizationId()));
        insertRule(policyId, STAGE, new Scope(fixture.insurerId(), insertOffering(fixture.productId(), fixture.documentVersion()), fixture.organizationId()));
        insertRule(policyId, STAGE, new Scope(fixture.insurerId(), fixture.offeringId(), insertOrganization()));
        long otherPolicy = draftPolicy(CONTRACT_DATE, null, 1, "CURRENT", fixture.documentVersion());
        insertRule(otherPolicy, STAGE, fixture.scope());
        activate(policyId);

        List<ResolvedCommissionRule> rules = commissionRuleRepository.findApplicableCommissionRules(
                policyId, fixture.contractId(), STAGE);

        assertThat(rules).extracting(ResolvedCommissionRule::getCommissionRuleId)
                .containsExactly(firstTie, secondTie, laterInstallment, fixed, generic);
        assertThat(rules).allSatisfy(rule -> {
            assertThat(rule.getFeeComponentType()).isEqualTo(FeeComponentType.CURRENT);
            assertThat(rule.getCommissionItemId()).isNotNull();
            assertThat(rule.getRoundingScale()).isZero();
            assertThat(rule.getRoundingMode()).isEqualTo(RoundingMode.HALF_UP);
            assertThat(rule.getPaymentConditionCode()).isNull();
            assertThat(rule.getBasisCode()).isEqualTo("MONTHLY_PREMIUM");
        });
        assertThat(rules.get(2)).satisfies(rule -> {
            assertThat(rule.getCalculationType()).isEqualTo(CalculationType.RATE);
            assertThat(rule.getAgentRankCode()).isEqualTo(AgentRankCode.FC);
            assertThat(rule.getInsurerId()).isEqualTo(fixture.insurerId());
            assertThat(rule.getProductOfferingId()).isEqualTo(fixture.offeringId());
            assertThat(rule.getOrganizationId()).isEqualTo(fixture.organizationId());
            assertThat(rule.getRatePct()).isEqualByComparingTo("123.456789");
            assertThat(rule.getFixedAmount()).isNull();
            assertThat(rule.getInstallmentFrom()).isEqualTo(2);
            assertThat(rule.getInstallmentTo()).isEqualTo(12);
        });
        assertThat(rules.get(3)).satisfies(rule -> {
            assertThat(rule.getCalculationType()).isEqualTo(CalculationType.FIXED);
            assertThat(rule.getFixedAmount()).isEqualByComparingTo("1234.50");
            assertThat(rule.getRatePct()).isNull();
        });
        assertThat(rules.get(4)).satisfies(rule -> {
            assertThat(rule.getAgentRankCode()).isNull();
            assertThat(rule.getInsurerId()).isNull();
            assertThat(rule.getProductOfferingId()).isNull();
            assertThat(rule.getOrganizationId()).isNull();
        });
        assertThat(commissionRuleRepository.findApplicableCommissionRules(
                policyId, fixture.contractId(), PaymentStage.GA_TO_FC)).hasSize(1);
        assertThat(commissionRuleRepository.findApplicableCommissionRules(policyId, Long.MAX_VALUE, STAGE)).isEmpty();
        assertThat(commissionRuleRepository.findApplicableCommissionRules(Long.MAX_VALUE, fixture.contractId(), STAGE)).isEmpty();
    }

    private List<ResolvedCommissionPolicy> policiesFor(long contractId, PaymentStage stage) {
        // 데모 정책과 독립적으로 이번 테스트가 작성한 정책들의 포함·제외를 검증한다.
        return policyVersionRepository.findApplicableCurrentCommissionPolicies(contractId, stage).stream()
                .filter(policy -> testPolicyIds.contains(policy.getPolicyVersionId()))
                .toList();
    }

    private long activePolicy(LocalDate from, LocalDate to, int version, String regime, String document,
                              PaymentStage stage, Scope scope) {
        long id = draftPolicy(from, to, version, regime, document);
        insertRule(id, stage, scope);
        activate(id);
        return id;
    }

    private long draftPolicy(LocalDate from, LocalDate to, int version, String regime, String document) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.policy_version (
                    policy_code, policy_name, policy_type, source_class, version_no,
                    effective_from, effective_to, status, fee_regime_code, basic_document_version
                ) VALUES (?, '수수료 조회 테스트', 'CURRENT_COMMISSION', 'PROJECT_ASSUMPTION', ?, ?, ?, 'DRAFT', ?, ?)
                RETURNING policy_version_id
                """, Long.class, "TEST-POLICY-" + UUID.randomUUID(), version, from, to, regime, document);
        testPolicyIds.add(id);
        return id;
    }

    private long insertRule(long policyId, PaymentStage stage, Scope scope) {
        return insertRule(policyId, stage, scope, null, 1, 100, new BigDecimal("1.000000"), null);
    }

    private long insertRule(long policyId, PaymentStage stage, Scope scope, String rank, int installment,
                            int priority, BigDecimal rate, BigDecimal fixed) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_rule (
                    policy_version_id, payment_stage, insurer_id, product_offering_id, organization_id,
                    agent_rank_code, commission_item_id, fee_component_type, installment_from, installment_to,
                    calculation_type, basis_code, rate_pct, fixed_amount, priority_no
                ) VALUES (?, ?, ?, ?, ?, ?, (SELECT min(commission_item_id) FROM fgc.commission_item),
                          'CURRENT', ?, 12, ?, 'MONTHLY_PREMIUM', ?, ?, ?)
                RETURNING commission_rule_id
                """, Long.class, policyId, stage.name(), scope.insurerId(), scope.offeringId(), scope.organizationId(),
                rank, installment, fixed == null ? "RATE" : "FIXED", rate, fixed, priority);
    }

    private void activate(long policyId) {
        if (writerId == null) {
            writerId = insertUser();
            approverId = insertUser();
        }
        jdbcTemplate.update("""
                UPDATE fgc.policy_version
                SET status = 'APPROVED', approved_at = clock_timestamp(), created_by = ?, approved_by = ?
                WHERE policy_version_id = ?
                """, writerId, approverId, policyId);
        jdbcTemplate.update("UPDATE fgc.policy_version SET status = 'ACTIVE' WHERE policy_version_id = ?", policyId);
    }

    private long insertUser() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.app_user (login_id, password_hash, user_name, role_id)
                VALUES (?, 'test-only', '수수료 조회 테스트', (SELECT min(role_id) FROM fgc.app_role))
                RETURNING user_id
                """, Long.class, "policy-test-" + UUID.randomUUID());
    }

    private Fixture insertFixture() {
        long insurerId = insertInsurer();
        long productId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.product (
                    insurer_id, insurer_product_code, standard_product_code, product_name, product_group_code
                ) VALUES (?, ?, ?, '수수료 테스트 상품', 'HEALTH_PROTECTION') RETURNING product_id
                """, Long.class, insurerId, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        String document = "DOC-" + UUID.randomUUID();
        long offeringId = insertOffering(productId, document);
        long agentId = jdbcTemplate.queryForObject("SELECT min(agent_id) FROM fgc.agent", Long.class);
        long organizationId = jdbcTemplate.queryForObject("SELECT organization_id FROM fgc.agent WHERE agent_id = ?", Long.class, agentId);
        long contractId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date, agent_id, organization_id,
                    premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium, payment_term_months
                ) VALUES (?, ?, ?, ?, ?, ?, 100000, 100000, 100000, 240)
                RETURNING contract_id
                """, Long.class, insurerId, offeringId, "TEST-" + UUID.randomUUID(), CONTRACT_DATE, agentId, organizationId);
        return new Fixture(insurerId, productId, offeringId, organizationId, contractId, document);
    }

    private long insertInsurer() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurer (insurer_code, insurer_name, insurer_type)
                VALUES (?, '수수료 테스트 보험사', 'LIFE') RETURNING insurer_id
                """, Long.class, "I-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private long insertOffering(long productId, String document) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.product_offering (
                    product_id, offering_version, sales_start_date, sales_end_date,
                    basic_document_version, basic_document_date, channel_code, fee_regime_code
                ) VALUES (?, ?, ?, ?, ?, ?, 'FACE_TO_FACE', 'CURRENT') RETURNING product_offering_id
                """, Long.class, productId, UUID.randomUUID().toString(), CONTRACT_DATE, CONTRACT_DATE, document, CONTRACT_DATE);
    }

    private long insertOrganization() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.organization (organization_code, organization_name, organization_type, effective_from)
                VALUES (?, '수수료 테스트 조직', 'TEAM', ?) RETURNING organization_id
                """, Long.class, UUID.randomUUID().toString(), CONTRACT_DATE);
    }

    private record Scope(Long insurerId, Long offeringId, Long organizationId) {
    }

    private record Fixture(long insurerId, long productId, long offeringId, long organizationId,
                           long contractId, String documentVersion) {
        Scope scope() {
            return new Scope(insurerId, offeringId, organizationId);
        }
    }
}
