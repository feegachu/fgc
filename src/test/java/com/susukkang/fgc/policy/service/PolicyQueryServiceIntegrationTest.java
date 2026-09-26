package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.PolicySourceClass;
import com.susukkang.fgc.common.code.PolicyStatus;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.dto.CapRuleItemResponse;
import com.susukkang.fgc.policy.dto.CapRuleSetResponse;
import com.susukkang.fgc.policy.dto.CommissionRuleResponse;
import com.susukkang.fgc.policy.dto.PolicyDetailResponse;
import com.susukkang.fgc.policy.dto.PolicyVersionResponse;
import com.susukkang.fgc.policy.dto.RefundRateLineResponse;
import com.susukkang.fgc.policy.dto.RefundRateTableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FGC-FUN-012·013: 정책·룰셋 조회 서비스의 JPA 통합 테스트.
 * 픽스처는 DRAFT 상태에서 자식 행을 넣는다 — APPROVED/ACTIVE 정책의 자식은
 * DB 트리거(fgc.guard_policy_child_mutation)가 INSERT 도 거부한다.
 */
@SpringBootTest
@Transactional
class PolicyQueryServiceIntegrationTest {

    @Autowired
    private PolicyQueryService policyQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String suffix;
    private String cap2026Code;
    private String cap2027Code;
    private String currentCode;
    private Long cap2026Id;
    private Long cap2027Id;
    private Long currentId;

    @BeforeEach
    void insertPolicyFixtures() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        cap2026Code = "IT-CAP-2026-" + suffix;
        cap2027Code = "IT-CAP-2027-" + suffix;
        currentCode = "IT-CUR-" + suffix;

        // 2026년에만 유효한 CAP_1200 (asOf 경계 검증용) — REG 근거 2건
        cap2026Id = insertPolicyVersion(cap2026Code, PolicyType.CAP_1200,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                new String[]{"REG-08", "REG-09"}, new String[]{"SRC-002"});
        // 2027년부터 유효한 CAP_1200 (열린 종료일)
        cap2027Id = insertPolicyVersion(cap2027Code, PolicyType.CAP_1200,
                LocalDate.of(2027, 1, 1), null,
                new String[]{"REG-08"}, new String[]{});
        // 유형 필터 검증용 CURRENT_COMMISSION
        currentId = insertPolicyVersion(currentCode, PolicyType.CURRENT_COMMISSION,
                LocalDate.of(2026, 1, 1), null,
                new String[]{}, new String[]{});
    }

    /** REG-19: 적용 시작일과 종료일은 모두 조회 기준일에 포함된다. */
    @Test
    void asOfFilterSelectsOnlyVersionsEffectiveOnThatDate() {
        List<PolicyVersionResponse> onEffectiveFrom = policyQueryService.findPolicyVersions(
                PolicyType.CAP_1200, LocalDate.of(2026, 1, 1), null);
        assertThat(onEffectiveFrom).extracting(PolicyVersionResponse::policyCode)
                .contains(cap2026Code)
                .doesNotContain(cap2027Code);

        List<PolicyVersionResponse> onEffectiveTo = policyQueryService.findPolicyVersions(
                PolicyType.CAP_1200, LocalDate.of(2026, 12, 31), null);
        assertThat(onEffectiveTo).extracting(PolicyVersionResponse::policyCode)
                .contains(cap2026Code)
                .doesNotContain(cap2027Code);

        List<PolicyVersionResponse> afterEffectiveTo = policyQueryService.findPolicyVersions(
                PolicyType.CAP_1200, LocalDate.of(2027, 1, 1), null);
        assertThat(afterEffectiveTo).extracting(PolicyVersionResponse::policyCode)
                .contains(cap2027Code)
                .doesNotContain(cap2026Code);
    }

    @Test
    void typeAndStatusFiltersNarrowTheList() {
        List<PolicyVersionResponse> capOnly = policyQueryService.findPolicyVersions(PolicyType.CAP_1200, null, null);
        assertThat(capOnly).extracting(PolicyVersionResponse::policyCode)
                .contains(cap2026Code, cap2027Code)
                .doesNotContain(currentCode);

        // DRAFT → APPROVED → ACTIVE 승격. 승인 정책은 작성자·승인자가 서로 다른 사용자여야 한다
        // (ck_policy_authorship, V6 무결성 강화 / REG-22)
        Long writerId = insertAppUser("it_writer_" + suffix);
        Long approverId = insertAppUser("it_approver_" + suffix);
        jdbcTemplate.update("""
                UPDATE fgc.policy_version
                   SET status = 'APPROVED', approved_at = clock_timestamp(),
                       created_by = ?, approved_by = ?
                 WHERE policy_version_id = ?
                """, writerId, approverId, currentId);
        jdbcTemplate.update("UPDATE fgc.policy_version SET status = 'ACTIVE' WHERE policy_version_id = ?", currentId);

        List<PolicyVersionResponse> activeOnly = policyQueryService.findPolicyVersions(null, null, PolicyStatus.ACTIVE);
        assertThat(activeOnly).extracting(PolicyVersionResponse::policyCode)
                .contains(currentCode)
                .doesNotContain(cap2026Code, cap2027Code);

        assertThat(activeOnly).filteredOn(row -> row.policyVersionId().equals(currentId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.createdBy()).isEqualTo("it_writer_" + suffix);
                    assertThat(row.approvedBy()).isEqualTo("it_approver_" + suffix);
                    assertThat(row.approvedAt()).isNotNull();
                    assertThat(row.status()).isEqualTo(PolicyStatus.ACTIVE);
                    assertThat(row.statusLabel()).isEqualTo(PolicyStatus.ACTIVE.label());
                });
        PolicyVersionResponse header = policyQueryService.findPolicyDetail(currentId).header();
        assertThat(header.createdBy()).isEqualTo("it_writer_" + suffix);
        assertThat(header.approvedBy()).isEqualTo("it_approver_" + suffix);
        assertThat(header.approvedAt()).isNotNull();
    }

    @Test
    void textArrayColumnsAreMappedToLists() {
        PolicyDetailResponse detail = policyQueryService.findPolicyDetail(cap2026Id);
        PolicyVersionResponse row = detail.header();

        assertThat(row).isNotNull();
        assertThat(row.regulationRefs()).containsExactly("REG-08", "REG-09");
        assertThat(detail.sourceRefs()).containsExactly("SRC-002");
        assertThat(row.status()).isEqualTo(PolicyStatus.DRAFT);
        assertThat(row.statusLabel()).isEqualTo(PolicyStatus.DRAFT.label());
        assertThat(row.policyType()).isEqualTo(PolicyType.CAP_1200);
        assertThat(row.policyTypeLabel()).isEqualTo(PolicyType.CAP_1200.label());
        assertThat(row.sourceClass()).isEqualTo(PolicySourceClass.GA_POLICY);
        assertThat(row.sourceClassLabel()).isEqualTo(PolicySourceClass.GA_POLICY.label());
        assertThat(row.createdBy()).isNull();
        assertThat(row.approvedBy()).isNull();
        assertThat(row.approvedAt()).isNull();

        PolicyDetailResponse empty = policyQueryService.findPolicyDetail(currentId);
        assertThat(empty.sourceRefs()).isEmpty();
        assertThat(empty.header().regulationRefs()).isEmpty();
        assertThat(empty.commissionRules()).isEmpty();
        assertThat(empty.capRuleSets()).isEmpty();
        assertThat(empty.refundRateTables()).isEmpty();

        assertThat(policyQueryService.findPolicyVersions(PolicyType.CAP_1200, null, null))
                .filteredOn(version -> version.policyVersionId().equals(cap2026Id))
                .singleElement()
                .satisfies(version -> assertThat(version.regulationRefs()).containsExactly("REG-08", "REG-09"));
    }

    @Test
    void rejectsAnUnknownPolicyVersionIdWithTheExistingNotFoundError() {
        assertThatThrownBy(() -> policyQueryService.findPolicyDetail(-1L))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_004);
                    assertThat(exception.getParams()).containsEntry("id", -1L);
                });
    }

    @Test
    void selectsCommissionRulesWithItemNames() {
        Long itemId = insertCommissionItem("IT_RULE_" + suffix, "통합 테스트 규칙 항목");
        insertCommissionRule(currentId, itemId, "FIXED", null, "500000.00", 200);
        insertCommissionRule(currentId, itemId, "RATE", "650.000000", null, 100);

        List<CommissionRuleResponse> rules = policyQueryService.findPolicyDetail(currentId).commissionRules();

        assertThat(rules).hasSize(2);
        assertThat(rules).extracting(CommissionRuleResponse::calculationType)
                .containsExactly("RATE", "FIXED");
        assertThat(rules).allSatisfy(rule -> {
            assertThat(rule.itemName()).isEqualTo("통합 테스트 규칙 항목");
            assertThat(rule.paymentStage()).isEqualTo(PaymentStage.GA_TO_FC);
            assertThat(rule.paymentStageLabel()).isEqualTo(PaymentStage.GA_TO_FC.label());
            assertThat(rule.insurerName()).isNull();
            assertThat(rule.productName()).isNull();
        });
        assertThat(rules).filteredOn(r -> "RATE".equals(r.calculationType()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.ratePct()).isEqualTo("650.000000");
                    assertThat(r.fixedAmount()).isNull();
                });
        assertThat(rules).filteredOn(r -> "FIXED".equals(r.calculationType()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.fixedAmount()).isEqualTo(500000L);
                    assertThat(r.ratePct()).isNull();
                });
    }

    @Test
    void selectsMultipleCapRuleSetsWithNestedItemDecisions() {
        Long includedItemId = insertCommissionItem("IT_INC_" + suffix, "산입 항목");
        Long excludedItemId = insertCommissionItem("IT_EXC_" + suffix, "제외 항목");
        // 원수사→GA 는 준법경영비 3%, GA→설계사는 0 (ck_cap_compliance_stage)
        Long insToGaSetId = insertCapRuleSet(cap2026Id, "INSURER_TO_GA", "3.0000");
        Long gaToFcSetId = insertCapRuleSet(cap2026Id, "GA_TO_FC", "0");
        insertCapRuleItem(insToGaSetId, includedItemId, true);
        insertCapRuleItem(insToGaSetId, excludedItemId, false);
        insertCapRuleItem(gaToFcSetId, includedItemId, true);

        List<CapRuleSetResponse> sets = policyQueryService.findPolicyDetail(cap2026Id).capRuleSets();

        assertThat(sets).hasSize(2);
        assertThat(sets).extracting(CapRuleSetResponse::paymentStage)
                .containsExactly(PaymentStage.GA_TO_FC, PaymentStage.INSURER_TO_GA);
        assertThat(sets).filteredOn(s -> PaymentStage.INSURER_TO_GA == s.paymentStage())
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.complianceDeductionPct()).isEqualTo("3.0000");
                    assertThat(s.items()).extracting(CapRuleItemResponse::itemCode)
                            .containsExactly("IT_EXC_" + suffix, "IT_INC_" + suffix);
                    assertThat(s.items())
                            .filteredOn(i -> "EXCLUDED".equals(i.inclusionStatus()))
                            .singleElement()
                            .satisfies(i -> {
                                assertThat(i.exclusionType()).isEqualTo("NEWCOMER_SUPPORT");
                                assertThat(i.evidenceRequiredYn()).isTrue();
                            });
                });
        assertThat(sets).filteredOn(s -> PaymentStage.GA_TO_FC == s.paymentStage())
                .singleElement()
                .satisfies(s -> assertThat(s.items()).hasSize(1));
    }

    @Test
    void selectsRefundRateTablesWithOrderedNestedLines() {
        Long insurerId = insertInsurer();
        Long productId = insertProduct(insurerId);
        Long table240Id = insertRefundRateTable(cap2026Id, insurerId, productId, 240);
        Long table120Id = insertRefundRateTable(cap2026Id, insurerId, productId, 120);
        insertRefundRateLine(table240Id, 2, "5.800000");
        insertRefundRateLine(table240Id, 1, "2.900000");
        insertRefundRateLine(table120Id, 1, "3.100000");

        List<RefundRateTableResponse> tables = policyQueryService.findPolicyDetail(cap2026Id).refundRateTables();

        assertThat(tables).hasSize(2);
        assertThat(tables).extracting(RefundRateTableResponse::paymentTermMonths)
                .containsExactly(120, 240);
        assertThat(tables).filteredOn(t -> t.paymentTermMonths() == 240)
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.insurerName()).isEqualTo("통합 테스트 보험");
                    assertThat(t.productName()).isEqualTo("통합 테스트 종신");
                    // 차월 순 정렬 — 입력은 2, 1 순서였다
                    assertThat(t.lines()).extracting(RefundRateLineResponse::contractMonthNo)
                            .containsExactly(1, 2);
                    assertThat(t.lines()).extracting(RefundRateLineResponse::refundRatePct)
                            .containsExactly("2.900000", "5.800000");
                });
    }

    @Test
    void retainsCapRuleSetsAndRefundRateTablesWithoutChildren() {
        Long capRuleSetId = insertCapRuleSet(cap2026Id, "GA_TO_FC", "0");
        Long insurerId = insertInsurer();
        Long productId = insertProduct(insurerId);
        Long refundRateTableId = insertRefundRateTable(cap2026Id, insurerId, productId, 240);

        PolicyDetailResponse detail = policyQueryService.findPolicyDetail(cap2026Id);

        assertThat(detail.capRuleSets()).singleElement().satisfies(set -> {
            assertThat(set.capRuleSetId()).isEqualTo(capRuleSetId);
            assertThat(set.items()).isEmpty();
        });
        assertThat(detail.refundRateTables()).singleElement().satisfies(table -> {
            assertThat(table.refundRateTableId()).isEqualTo(refundRateTableId);
            assertThat(table.lines()).isEmpty();
        });
    }

    @Test
    void ordersVersionsByPolicyCodeThenVersionNumber() {
        Long laterVersionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.policy_version (
                    policy_code, policy_name, policy_type, source_class, version_no,
                    effective_from, status
                ) VALUES (?, '두 번째 버전', 'CAP_1200', 'GA_POLICY', 2, DATE '2027-01-01', 'DRAFT')
                RETURNING policy_version_id
                """, Long.class, cap2026Code);

        assertThat(policyQueryService.findPolicyVersions(null, null, null))
                .filteredOn(version -> List.of(cap2026Id, laterVersionId, cap2027Id, currentId)
                        .contains(version.policyVersionId()))
                .extracting(PolicyVersionResponse::policyVersionId)
                .containsExactly(cap2026Id, laterVersionId, cap2027Id, currentId);
    }

    @Test
    void returnsAnEmptyListWhenNoPolicyIsEffective() {
        assertThat(policyQueryService.findPolicyVersions(null, LocalDate.of(1900, 1, 1), null))
                .isEmpty();
    }

    private Long insertPolicyVersion(String code, PolicyType type, LocalDate from, LocalDate to,
                                     String[] regulationRefs, String[] sourceRefs) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.policy_version (
                    policy_code, policy_name, policy_type, source_class, version_no,
                    effective_from, effective_to, status, regulation_refs, source_refs
                ) VALUES (?, ?, ?, 'GA_POLICY', 1, ?, ?, 'DRAFT', ?, ?)
                RETURNING policy_version_id
                """, Long.class, code, "통합 테스트 정책 " + code, type.name(), from, to,
                regulationRefs, sourceRefs);
    }

    private Long insertAppUser(String loginId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.app_user (login_id, password_hash, user_name, role_id)
                VALUES (?, 'x', ?, (SELECT min(role_id) FROM fgc.app_role))
                RETURNING user_id
                """, Long.class, loginId, loginId);
    }

    private Long insertCommissionItem(String itemCode, String itemName) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_item (
                    item_code, item_name, cashflow_type, item_category, effective_from, active_yn
                ) VALUES (?, ?, 'PAYMENT', 'SALES', DATE '2026-01-01', TRUE)
                RETURNING commission_item_id
                """, Long.class, itemCode, itemName);
    }

    /** priority_no 는 uq_commission_rule 유니크 키에 포함되므로 규칙마다 다르게 준다. */
    private void insertCommissionRule(Long policyVersionId, Long itemId,
                                      String calculationType, String ratePct, String fixedAmount, int priorityNo) {
        jdbcTemplate.update("""
                INSERT INTO fgc.commission_rule (
                    policy_version_id, payment_stage, commission_item_id, fee_component_type,
                    installment_from, installment_to, calculation_type, basis_code,
                    rate_pct, fixed_amount, priority_no
                ) VALUES (?, 'GA_TO_FC', ?, 'CURRENT', 1, 1, ?, 'MONTHLY_PREMIUM', ?::numeric, ?::numeric, ?)
                """, policyVersionId, itemId, calculationType, ratePct, fixedAmount, priorityNo);
    }

    private Long insertCapRuleSet(Long policyVersionId, String paymentStage, String compliancePct) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.cap_rule_set (
                    policy_version_id, payment_stage, contract_date_from,
                    premium_multiplier, compliance_deduction_pct, refund_addition_condition
                ) VALUES (?, ?, DATE '2026-07-01', 12.0000, ?::numeric, 'STANDARD_DEDUCTION_80')
                RETURNING cap_rule_set_id
                """, Long.class, policyVersionId, paymentStage, compliancePct);
    }

    private void insertCapRuleItem(Long capRuleSetId, Long itemId, boolean included) {
        if (included) {
            jdbcTemplate.update("""
                    INSERT INTO fgc.cap_rule_item (
                        cap_rule_set_id, commission_item_id, inclusion_status,
                        attribution_method, decision_reason
                    ) VALUES (?, ?, 'INCLUDED', 'DIRECT', '통합 테스트 산입')
                    """, capRuleSetId, itemId);
        } else {
            // EXCLUDED 는 exclusion_type + evidence_required_yn=TRUE 필수 (ck_cap_exclusion_evidence)
            jdbcTemplate.update("""
                    INSERT INTO fgc.cap_rule_item (
                        cap_rule_set_id, commission_item_id, inclusion_status, exclusion_type,
                        evidence_required_yn, attribution_method, decision_reason
                    ) VALUES (?, ?, 'EXCLUDED', 'NEWCOMER_SUPPORT', TRUE, 'MANUAL_REVIEW', '통합 테스트 제외')
                    """, capRuleSetId, itemId);
        }
    }

    private Long insertInsurer() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurer (insurer_code, insurer_name, insurer_type)
                VALUES (?, '통합 테스트 보험', 'LIFE')
                RETURNING insurer_id
                """, Long.class, "IT_INS_" + suffix);
    }

    private Long insertProduct(Long insurerId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.product (
                    insurer_id, insurer_product_code, standard_product_code, product_name, product_group_code
                ) VALUES (?, ?, ?, '통합 테스트 종신', 'WHOLE_LIFE')
                RETURNING product_id
                """, Long.class, insurerId, "IT_PRD_" + suffix, "IT_STD_" + suffix);
    }

    private Long insertRefundRateTable(Long policyVersionId, Long insurerId, Long productId, int termMonths) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.refund_rate_table (
                    policy_version_id, insurer_id, product_id, payment_term_months,
                    channel_code, standard_deduction_80_yn, effective_from,
                    source_product_code, source_document_ref
                ) VALUES (?, ?, ?, ?, 'FC', TRUE, DATE '2026-01-01', ?, '통합 테스트 FAQ')
                RETURNING refund_rate_table_id
                """, Long.class, policyVersionId, insurerId, productId, termMonths, "IT_SRC_" + suffix);
    }

    private void insertRefundRateLine(Long tableId, int monthNo, String ratePct) {
        jdbcTemplate.update("""
                INSERT INTO fgc.refund_rate_line (refund_rate_table_id, contract_month_no, refund_rate_pct)
                VALUES (?, ?, ?::numeric)
                """, tableId, monthNo, ratePct);
    }
}
