package com.susukkang.fgc.policy.repository;

import com.susukkang.fgc.common.code.PolicySourceClass;
import com.susukkang.fgc.common.code.PolicyStatus;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.policy.dto.PolicyDraft;
import com.susukkang.fgc.policy.entity.PolicyVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 PostgreSQL 트리거·트랜잭션으로 헤더/분급 규칙 동시 수정과 실패 원자성을 검증한다. */
@SpringBootTest
class PolicyWriteRepositoryIntegrationTest {
    @Autowired private PolicyWriteRepository writeRepository;
    @Autowired private PolicyVersionRepository policyRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;
    private Long policyId;
    private Long feeId;
    private String code;

    @BeforeEach
    void createCommittedDraft() {
        transactions = new TransactionTemplate(transactionManager);
        code = "IT-WRITE-" + UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            Long actor = jdbc.queryForObject("SELECT MIN(user_id) FROM fgc.app_user", Long.class);
            PolicyDraft draft = document(null, PolicyType.FOUR_YEAR_COMMISSION, "FOUR_YEAR_2027", "BD-OLD", 48, List.of());
            PolicyVersion policy = policyRepository.saveAndFlush(PolicyVersion.draft(draft, 1, actor));
            policyId = policy.getPolicyVersionId();
            writeRepository.replaceChildren(policyId, draft);
            feeId = jdbc.queryForObject("SELECT fee_regime_ruleset_id FROM fgc.fee_regime_ruleset WHERE policy_version_id = ?", Long.class, policyId);
            jdbc.update("""
                    INSERT INTO fgc.fee_regime_ruleset_limit (fee_regime_ruleset_id, limit_code, limit_pct, includes_indirect_expense_yn)
                    VALUES (?, 'UPFRONT_TOTAL', 1.234567, true)
                    """, feeId);
        });
    }

    @AfterEach
    void removeOwnDraft() {
        if (policyId == null) return;
        transactions.executeWithoutResult(status -> {
            jdbc.update("UPDATE fgc.policy_version SET status = 'DRAFT' WHERE policy_version_id = ? AND status = 'REVIEW'", policyId);
            jdbc.update("DELETE FROM fgc.policy_source_detail WHERE policy_version_id = ?", policyId);
            jdbc.update("DELETE FROM fgc.policy_parameter WHERE policy_version_id = ?", policyId);
            jdbc.update("DELETE FROM fgc.commission_rule WHERE policy_version_id = ?", policyId);
            jdbc.update("""
                    DELETE FROM fgc.fee_regime_ruleset_limit WHERE fee_regime_ruleset_id IN
                    (SELECT fee_regime_ruleset_id FROM fgc.fee_regime_ruleset WHERE policy_version_id = ?)
                    """, policyId);
            jdbc.update("DELETE FROM fgc.fee_regime_ruleset WHERE policy_version_id = ?", policyId);
            jdbc.update("DELETE FROM fgc.policy_version WHERE policy_version_id = ?", policyId);
        });
    }

    @Test
    void changesHeaderAndFeeKeyTogetherWhileRetainingRulesetIdentityAndLimits() {
        Long beforeLimitId = jdbc.queryForObject("SELECT fee_regime_ruleset_limit_id FROM fgc.fee_regime_ruleset_limit WHERE fee_regime_ruleset_id = ?",
                Long.class, feeId);
        OffsetDateTime before = jdbc.queryForObject("SELECT updated_at FROM fgc.policy_version WHERE policy_version_id = ?",
                (rs, row) -> rs.getObject(1, OffsetDateTime.class), policyId);

        transactions.executeWithoutResult(status -> {
            PolicyVersion policy = writeRepository.lockPolicy(policyId).orElseThrow();
            PolicyDraft changed = document(feeId, PolicyType.SEVEN_YEAR_COMMISSION, "SEVEN_YEAR_2029", "BD-NEW", 84, List.of());
            assertThat(writeRepository.verifyChildOwnership(policyId, changed)).isTrue();
            writeRepository.updateDraft(policy, changed);
            assertThat(policy.getUpdatedAt().toInstant()).isAfter(before.toInstant());
            policy.transition(PolicyStatus.REVIEW, null);
            policyRepository.saveAndFlush(policy);
        });

        transactions.executeWithoutResult(status -> {
            PolicyDraft stored = writeRepository.readDraft(policyRepository.findById(policyId).orElseThrow());
            assertThat(stored.policyType()).isEqualTo(PolicyType.SEVEN_YEAR_COMMISSION);
            assertThat(stored.basicDocumentVersion()).isEqualTo("BD-NEW");
            assertThat(stored.feeRegimeRules()).singleElement().satisfies(rule -> {
                assertThat(rule.feeRegimeRulesetId()).isEqualTo(feeId);
                assertThat(rule.feeRegimeCode()).isEqualTo("SEVEN_YEAR_2029");
                assertThat(rule.maxInstallments()).isEqualTo(84);
                assertThat(rule.basicDocumentVersion()).isEqualTo("BD-NEW");
            });
            assertThat(jdbc.queryForObject("SELECT limit_pct FROM fgc.fee_regime_ruleset_limit WHERE fee_regime_ruleset_id = ?",
                    BigDecimal.class, feeId)).isEqualByComparingTo("1.234567");
            assertThat(jdbc.queryForObject("SELECT fee_regime_ruleset_limit_id FROM fgc.fee_regime_ruleset_limit WHERE fee_regime_ruleset_id = ?",
                    Long.class, feeId)).isEqualTo(beforeLimitId);
            assertThat(jdbc.queryForObject("SELECT includes_indirect_expense_yn FROM fgc.fee_regime_ruleset_limit WHERE fee_regime_ruleset_id = ?",
                    Boolean.class, feeId)).isTrue();
        });
    }

    @Test
    void failedChildInsertRollsBackHeaderAndDetachedRulesAndLimits() {
        // 85 is deliberately sent below validation to exercise the DB's actual failure boundary.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            PolicyVersion policy = writeRepository.lockPolicy(policyId).orElseThrow();
            writeRepository.updateDraft(policy,
                    document(feeId, PolicyType.SEVEN_YEAR_COMMISSION, "SEVEN_YEAR_2029", "BD-NEW", 85, List.of()));
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT policy_type FROM fgc.policy_version WHERE policy_version_id = ?",
                String.class, policyId)).isEqualTo("FOUR_YEAR_COMMISSION");
        assertThat(jdbc.queryForObject("SELECT max_installments FROM fgc.fee_regime_ruleset WHERE fee_regime_ruleset_id = ?",
                Integer.class, feeId)).isEqualTo(48);
        assertThat(jdbc.queryForObject("SELECT limit_pct FROM fgc.fee_regime_ruleset_limit WHERE fee_regime_ruleset_id = ?",
                BigDecimal.class, feeId)).isEqualByComparingTo("1.234567");
    }

    @Test
    void swapsRetainedCommissionAndFeeIntervalsWithoutIntermediateUniqueConflicts() {
        transactions.executeWithoutResult(status -> {
            jdbc.update("UPDATE fgc.fee_regime_ruleset SET sales_start_date_to = DATE '2035-06-30' WHERE fee_regime_ruleset_id = ?", feeId);
            jdbc.update("""
                    INSERT INTO fgc.fee_regime_ruleset
                    (policy_version_id, fee_regime_code, sales_start_date_from, basic_document_version, max_installments)
                    VALUES (?, 'FOUR_YEAR_2027', DATE '2035-07-01', 'BD-OLD', 48)
                    """, policyId);
            Long itemId = jdbc.queryForObject("SELECT MIN(commission_item_id) FROM fgc.commission_item", Long.class);
            for (int from : List.of(1, 13)) {
                jdbc.update("""
                        INSERT INTO fgc.commission_rule
                        (policy_version_id, payment_stage, commission_item_id, fee_component_type,
                         installment_from, installment_to, calculation_type, basis_code, fixed_amount, priority_no)
                        VALUES (?, 'GA_TO_FC', ?, 'UPFRONT', ?, ?, 'FIXED', 'MONTHLY_EQUIVALENT_FIRST_PREMIUM', 100, 100)
                        """, policyId, itemId, from, from + 11);
            }
        });
        List<Long> commissionIds = jdbc.queryForList("SELECT commission_rule_id FROM fgc.commission_rule WHERE policy_version_id = ? ORDER BY commission_rule_id", Long.class, policyId);
        List<Long> feeIds = jdbc.queryForList("SELECT fee_regime_ruleset_id FROM fgc.fee_regime_ruleset WHERE policy_version_id = ? ORDER BY fee_regime_ruleset_id", Long.class, policyId);
        OffsetDateTime originalCreatedAt = jdbc.queryForObject("SELECT created_at FROM fgc.fee_regime_ruleset WHERE fee_regime_ruleset_id = ?",
                (rs, row) -> rs.getObject(1, OffsetDateTime.class), feeId);

        transactions.executeWithoutResult(status -> {
            PolicyVersion policy = writeRepository.lockPolicy(policyId).orElseThrow();
            PolicyDraft before = writeRepository.readDraft(policy);
            List<PolicyDraft.CommissionRuleDraft> swappedCommission = List.of(
                    withInstallments(before.commissionRules().get(0), 13, 24),
                    withInstallments(before.commissionRules().get(1), 1, 12));
            List<PolicyDraft.FeeRegimeRuleDraft> swappedFee = List.of(
                    new PolicyDraft.FeeRegimeRuleDraft(feeIds.get(0), "FOUR_YEAR_2027", LocalDate.of(2035, 7, 1), null,
                            "BD-OLD", null, null, 48, null, null, false, List.of()),
                    new PolicyDraft.FeeRegimeRuleDraft(feeIds.get(1), "FOUR_YEAR_2027", LocalDate.of(2035, 1, 1), LocalDate.of(2035, 6, 30),
                            "BD-OLD", null, null, 48, null, null, false, List.of()));
            PolicyDraft changed = new PolicyDraft(before.policyCode(), before.policyName(), before.policyType(), before.sourceClass(),
                    before.effectiveFrom(), before.effectiveTo(), before.feeRegimeCode(), before.basicDocumentVersion(),
                    before.regulationRefs(), before.sourceRefs(), before.sourceDetails(), swappedCommission, swappedFee,
                    before.parameters(), before.clawbackRules(), before.expectedUpdatedAt());
            writeRepository.updateDraft(policy, changed);
        });

        assertThat(jdbc.queryForList("SELECT installment_from FROM fgc.commission_rule WHERE policy_version_id = ? ORDER BY commission_rule_id", Integer.class, policyId))
                .containsExactly(13, 1);
        assertThat(jdbc.queryForList("SELECT commission_rule_id FROM fgc.commission_rule WHERE policy_version_id = ? ORDER BY commission_rule_id", Long.class, policyId))
                .isEqualTo(commissionIds);
        assertThat(jdbc.<LocalDate>queryForObject("SELECT sales_start_date_from FROM fgc.fee_regime_ruleset WHERE fee_regime_ruleset_id = ?",
                (rs, row) -> rs.getObject(1, LocalDate.class), feeId))
                .isEqualTo(LocalDate.of(2035, 7, 1));
        assertThat(jdbc.queryForList("SELECT fee_regime_ruleset_id FROM fgc.fee_regime_ruleset WHERE policy_version_id = ? ORDER BY fee_regime_ruleset_id", Long.class, policyId))
                .isEqualTo(feeIds);
        assertThat(jdbc.<OffsetDateTime>queryForObject("SELECT created_at FROM fgc.fee_regime_ruleset WHERE fee_regime_ruleset_id = ?",
                (rs, row) -> rs.getObject(1, OffsetDateTime.class), feeId)).isEqualTo(originalCreatedAt);
        assertThat(jdbc.queryForObject("SELECT limit_pct FROM fgc.fee_regime_ruleset_limit WHERE fee_regime_ruleset_id = ?", BigDecimal.class, feeId))
                .isEqualByComparingTo("1.234567");
    }

    private static PolicyDraft.CommissionRuleDraft withInstallments(PolicyDraft.CommissionRuleDraft row, int from, int to) {
        return new PolicyDraft.CommissionRuleDraft(row.commissionRuleId(), row.paymentStage(), row.insurerId(),
                row.productOfferingId(), row.organizationId(), row.agentRankCode(), row.commissionItemId(), row.feeComponentType(),
                from, to, row.calculationType(), row.basisCode(), row.ratePct(), row.fixedAmount(), row.equalMonthlyYn(),
                row.paymentConditionCode(), row.priorityNo());
    }

    @Test
    void sourceDetailsCannotBeChangedOnceSubmitted() {
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO fgc.policy_source_detail (policy_version_id, ref, title) VALUES (?, 'SRC-TEST', '원본')", policyId);
            jdbc.update("UPDATE fgc.policy_version SET status = 'REVIEW' WHERE policy_version_id = ?", policyId);
        });
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                jdbc.update("UPDATE fgc.policy_source_detail SET title = '변경' WHERE policy_version_id = ?", policyId)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT title FROM fgc.policy_source_detail WHERE policy_version_id = ?",
                String.class, policyId)).isEqualTo("원본");
    }

    private PolicyDraft document(Long ruleId, PolicyType type, String regime, String documentVersion,
                                 int maxInstallments, List<PolicyDraft.SourceDetailDraft> sources) {
        return new PolicyDraft(code, "정책 저장 트랜잭션 검증", type, PolicySourceClass.GA_POLICY,
                LocalDate.of(2035, 1, 1), LocalDate.of(2035, 12, 31), regime, documentVersion,
                List.of(), List.of(), sources, List.of(), List.of(new PolicyDraft.FeeRegimeRuleDraft(
                ruleId, regime, LocalDate.of(2035, 1, 1), null, documentVersion, null, null,
                maxInstallments, null, null, false, List.of())), List.of(), List.of(), null);
    }
}
