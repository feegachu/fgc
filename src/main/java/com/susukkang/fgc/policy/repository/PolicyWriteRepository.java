package com.susukkang.fgc.policy.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.policy.dto.PolicyApprovalActionResponse;
import com.susukkang.fgc.policy.dto.PolicyApplicationPeriod;
import com.susukkang.fgc.policy.dto.PolicyDraft;
import com.susukkang.fgc.policy.dto.PolicyFeeLimit;
import com.susukkang.fgc.policy.dto.PolicyWriteResult;
import com.susukkang.fgc.policy.entity.PolicyVersion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** FUN-017 작성 트랜잭션의 잠금·자식 저장·멱등 결과·승인 이력. 모든 SQL 값은 바인딩한다. */
@Repository
@RequiredArgsConstructor
public class PolicyWriteRepository {
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public Optional<PolicyVersion> lockPolicy(Long id) {
        PolicyVersion policy = entityManager.find(PolicyVersion.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (policy != null) {
            entityManager.refresh(policy, LockModeType.PESSIMISTIC_WRITE);
        }
        return Optional.ofNullable(policy);
    }

    public void refresh(PolicyVersion policy) {
        entityManager.flush();
        entityManager.refresh(policy);
    }

    /** 자식만 수정한 요청도 낙관적 동시성 토큰을 새로 발급한다. */
    public void touchPolicy(PolicyVersion policy) {
        entityManager.flush();
        jdbc.update("UPDATE fgc.policy_version SET updated_at = clock_timestamp() WHERE policy_version_id = ?",
                policy.getPolicyVersionId());
        entityManager.refresh(policy);
    }

    public void touchPolicy(Long id) {
        entityManager.flush();
        jdbc.update("UPDATE fgc.policy_version SET updated_at = clock_timestamp() WHERE policy_version_id = ?", id);
    }

    /** 아직 헤더가 없는 코드도 직렬화하여 최초 생성과 복제의 버전 채번을 보호한다. */
    public void lockPolicyCode(String code) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "policy-code:" + code);
    }

    /** 서로 다른 코드 사이의 적용 범위 검사와 활성화를 같은 잠금으로 보호한다. */
    public void lockActivationScope() {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended('policy-activation', 0))");
    }

    /** 활성화 전역 잠금 안에서 호출한다. 같은 코드의 ACTIVE를 일정 순서로 잠근다. */
    public List<PolicyVersion> lockActiveVersions(String policyCode) {
        return jdbc.queryForList("""
                SELECT policy_version_id FROM fgc.policy_version
                WHERE policy_code = ? AND status = 'ACTIVE' ORDER BY policy_version_id
                """, Long.class, policyCode).stream().map(id -> lockPolicy(id).orElseThrow()).toList();
    }

    public int nextVersion(String code) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(version_no), 0) + 1 FROM fgc.policy_version WHERE policy_code = ?",
                Integer.class, code);
    }

    public boolean tryLockRequest(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class, "policy-request:" + key));
    }

    public record StoredRequest(Long actorId, String operation, String requestHash, String responseBody) { }

    public Optional<StoredRequest> findRequest(String key) {
        return jdbc.query("""
                SELECT requested_by, operation, request_hash, response_body::text
                FROM fgc.policy_write_request WHERE idempotency_key = ?
                """, (rs, row) -> new StoredRequest(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), key)
                .stream().findFirst();
    }

    public void saveRequest(String key, Long actorId, String operation, String hash, PolicyWriteResult response) {
        jdbc.update("""
                INSERT INTO fgc.policy_write_request
                    (idempotency_key, requested_by, operation, request_hash, response_body, policy_version_id)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """, key, actorId, operation, hash, json(response), response.policyVersionId());
    }

    public boolean verifyChildOwnership(Long id, PolicyDraft draft) {
        Set<Long> commissionIds = new HashSet<>(jdbc.queryForList(
                "SELECT commission_rule_id FROM fgc.commission_rule WHERE policy_version_id = ?", Long.class, id));
        Set<Long> feeIds = new HashSet<>(jdbc.queryForList(
                "SELECT fee_regime_ruleset_id FROM fgc.fee_regime_ruleset WHERE policy_version_id = ?", Long.class, id));
        return draft.commissionRules().stream().allMatch(row -> row.commissionRuleId() == null || commissionIds.contains(row.commissionRuleId()))
                && draft.feeRegimeRules().stream().allMatch(row -> row.feeRegimeRulesetId() == null || feeIds.contains(row.feeRegimeRulesetId()));
    }

    /**
     * 전체 문서 PUT은 기존 규칙을 분리한 뒤 같은 ID·생성시각·한도로 복원한다.
     * 두 행의 구간을 맞바꾸어도 중간 UPDATE가 UNIQUE/EXCLUDE에 충돌하지 않으며,
     * V59의 헤더·분급 규칙 양방향 BEFORE 검사에도 중간 불일치를 노출하지 않는다.
     * 헤더 잠금과 호출자 트랜잭션 안에서 실행되므로 외부에서는 완성된 변경만 보인다.
     */
    public void updateDraft(PolicyVersion policy, PolicyDraft draft) {
        Long id = policy.getPolicyVersionId();
        Map<Long, OffsetDateTime> detachedCommissionRows = creationTimes(false, id);
        Map<Long, OffsetDateTime> detachedFeeRows = creationTimes(true, id);
        List<FeeLimit> savedLimits = jdbc.query("""
                SELECT l.fee_regime_ruleset_limit_id, l.fee_regime_ruleset_id, l.limit_code,
                       l.limit_pct, l.includes_indirect_expense_yn, l.created_at
                FROM fgc.fee_regime_ruleset_limit l
                JOIN fgc.fee_regime_ruleset r ON r.fee_regime_ruleset_id = l.fee_regime_ruleset_id
                WHERE r.policy_version_id = ?
                """, (rs, row) -> new FeeLimit(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getBigDecimal(4), rs.getBoolean(5), rs.getObject(6, OffsetDateTime.class)), id);
        jdbc.update("DELETE FROM fgc.commission_rule WHERE policy_version_id = ?", id);
        jdbc.update("""
                DELETE FROM fgc.fee_regime_ruleset_limit WHERE fee_regime_ruleset_id IN
                (SELECT fee_regime_ruleset_id FROM fgc.fee_regime_ruleset WHERE policy_version_id = ?)
                """, id);
        jdbc.update("DELETE FROM fgc.fee_regime_ruleset WHERE policy_version_id = ?", id);
        policy.revise(draft);
        entityManager.flush();
        replaceChildren(id, draft, detachedCommissionRows, detachedFeeRows);
        Set<Long> retainedFeeIds = new HashSet<>();
        draft.feeRegimeRules().forEach(row -> retainedFeeIds.add(row.feeRegimeRulesetId()));
        for (FeeLimit limit : savedLimits) {
            if (retainedFeeIds.contains(limit.rulesetId())) {
                jdbc.update("""
                        INSERT INTO fgc.fee_regime_ruleset_limit
                        (fee_regime_ruleset_limit_id, fee_regime_ruleset_id, limit_code, limit_pct, includes_indirect_expense_yn, created_at)
                        OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, ?, ?)
                        """, limit.id(), limit.rulesetId(), limit.code(), limit.pct(), limit.indirect(), limit.createdAt());
            }
        }
        touchPolicy(policy);
    }

    public Map<Long, Set<PolicyFeeLimit>> readFeeLimits(Long policyId) {
        Map<Long, Set<PolicyFeeLimit>> limits = new LinkedHashMap<>();
        org.springframework.jdbc.core.RowCallbackHandler collect = rs -> limits
                .computeIfAbsent(rs.getLong(1), ignored -> new HashSet<>())
                .add(new PolicyFeeLimit(rs.getString(2), rs.getBigDecimal(3), rs.getBoolean(4)));
        jdbc.query("""
                SELECT l.fee_regime_ruleset_id, l.limit_code, l.limit_pct, l.includes_indirect_expense_yn
                FROM fgc.fee_regime_ruleset_limit l
                JOIN fgc.fee_regime_ruleset r ON r.fee_regime_ruleset_id = l.fee_regime_ruleset_id
                WHERE r.policy_version_id = ?
                """, collect, policyId);
        return limits;
    }

    private Map<Long, OffsetDateTime> creationTimes(boolean feeRegime, Long policyId) {
        Map<Long, OffsetDateTime> times = new LinkedHashMap<>();
        org.springframework.jdbc.core.RowCallbackHandler collect = rs -> times.put(rs.getLong(1), rs.getObject(2, OffsetDateTime.class));
        if (feeRegime) {
            jdbc.query("SELECT fee_regime_ruleset_id, created_at FROM fgc.fee_regime_ruleset WHERE policy_version_id = ?", collect, policyId);
        } else {
            jdbc.query("SELECT commission_rule_id, created_at FROM fgc.commission_rule WHERE policy_version_id = ?", collect, policyId);
        }
        return times;
    }

    private record FeeLimit(Long id, Long rulesetId, String code, java.math.BigDecimal pct,
                            boolean indirect, OffsetDateTime createdAt) { }

    /** 유지한 자식 ID는 UPDATE하여 분급 한도 등 요청에 없는 부속 데이터를 보존한다. */
    public void replaceChildren(Long id, PolicyDraft draft) {
        replaceChildren(id, draft, Map.of(), Map.of());
    }

    private void replaceChildren(Long id, PolicyDraft draft, Map<Long, OffsetDateTime> detachedCommissionRows,
                                 Map<Long, OffsetDateTime> detachedFeeRows) {
        entityManager.flush();
        Set<Long> retainedCommissionIds = new HashSet<>();
        draft.commissionRules().forEach(row -> retainedCommissionIds.add(row.commissionRuleId()));
        for (Long oldId : jdbc.queryForList("SELECT commission_rule_id FROM fgc.commission_rule WHERE policy_version_id = ?", Long.class, id)) {
            if (!retainedCommissionIds.contains(oldId)) {
                jdbc.update("DELETE FROM fgc.commission_rule WHERE commission_rule_id = ? AND policy_version_id = ?", oldId, id);
            }
        }
        for (PolicyDraft.CommissionRuleDraft rule : draft.commissionRules()) {
            saveCommission(id, rule, rule.commissionRuleId() == null ? null : detachedCommissionRows.get(rule.commissionRuleId()));
        }

        Set<Long> retainedFeeIds = new HashSet<>();
        draft.feeRegimeRules().forEach(row -> retainedFeeIds.add(row.feeRegimeRulesetId()));
        for (Long oldId : jdbc.queryForList("SELECT fee_regime_ruleset_id FROM fgc.fee_regime_ruleset WHERE policy_version_id = ?", Long.class, id)) {
            if (!retainedFeeIds.contains(oldId)) {
                jdbc.update("DELETE FROM fgc.fee_regime_ruleset_limit WHERE fee_regime_ruleset_id = ?", oldId);
                jdbc.update("DELETE FROM fgc.fee_regime_ruleset WHERE fee_regime_ruleset_id = ? AND policy_version_id = ?", oldId, id);
            }
        }
        for (PolicyDraft.FeeRegimeRuleDraft rule : draft.feeRegimeRules()) {
            saveFeeRule(id, rule, rule.feeRegimeRulesetId() == null ? null : detachedFeeRows.get(rule.feeRegimeRulesetId()));
        }
        jdbc.update("DELETE FROM fgc.policy_parameter WHERE policy_version_id = ?", id);
        for (PolicyDraft.ParameterDraft parameter : draft.parameters()) {
            jdbc.update("""
                    INSERT INTO fgc.policy_parameter (policy_version_id, parameter_key, parameter_value, description)
                    VALUES (?, ?, ?::jsonb, ?)
                    """, id, parameter.key(), json(parameter.value()), parameter.description());
        }
        jdbc.update("DELETE FROM fgc.policy_source_detail WHERE policy_version_id = ?", id);
        for (PolicyDraft.SourceDetailDraft source : draft.sourceDetails()) {
            jdbc.update("""
                    INSERT INTO fgc.policy_source_detail (policy_version_id, ref, title, explanation, url)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, source.ref(), source.title(), source.explanation(), source.url());
        }
    }

    private void saveCommission(Long policyId, PolicyDraft.CommissionRuleDraft rule, OffsetDateTime createdAt) {
        boolean restoreIdentity = createdAt != null;
        List<Object> values = new ArrayList<>();
        values.add(rule.paymentStage().name());
        values.add(rule.insurerId());
        values.add(rule.productOfferingId());
        values.add(rule.organizationId());
        values.add(rule.agentRankCode() == null ? null : rule.agentRankCode().name());
        values.add(rule.commissionItemId());
        values.add(rule.feeComponentType().name());
        values.add(rule.installmentFrom());
        values.add(rule.installmentTo());
        values.add(rule.calculationType().name());
        values.add(rule.basisCode());
        values.add(rule.ratePct());
        values.add(rule.fixedAmount());
        values.add(rule.equalMonthlyYn());
        values.add(rule.paymentConditionCode());
        values.add(rule.priorityNo());
        if (rule.commissionRuleId() == null || restoreIdentity) {
            values.add(policyId);
            if (restoreIdentity) {
                values.add(rule.commissionRuleId());
                values.add(createdAt);
            }
            if (restoreIdentity) {
                jdbc.update("""
                    INSERT INTO fgc.commission_rule
                      (payment_stage, insurer_id, product_offering_id, organization_id, agent_rank_code,
                       commission_item_id, fee_component_type, installment_from, installment_to,
                       calculation_type, basis_code, rate_pct, fixed_amount, equal_monthly_yn,
                       payment_condition_code, priority_no, policy_version_id, commission_rule_id, created_at)
                    OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, values.toArray());
            } else {
                jdbc.update("""
                    INSERT INTO fgc.commission_rule
                      (payment_stage, insurer_id, product_offering_id, organization_id, agent_rank_code,
                       commission_item_id, fee_component_type, installment_from, installment_to,
                       calculation_type, basis_code, rate_pct, fixed_amount, equal_monthly_yn,
                       payment_condition_code, priority_no, policy_version_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, values.toArray());
            }
        } else {
            values.add(rule.commissionRuleId());
            values.add(policyId);
            int changed = jdbc.update("""
                    UPDATE fgc.commission_rule SET payment_stage=?, insurer_id=?, product_offering_id=?,
                      organization_id=?, agent_rank_code=?, commission_item_id=?, fee_component_type=?,
                      installment_from=?, installment_to=?, calculation_type=?, basis_code=?, rate_pct=?,
                      fixed_amount=?, equal_monthly_yn=?, payment_condition_code=?, priority_no=?,
                      rounding_scale=0, rounding_mode='HALF_UP', rule_expression='{}'::jsonb
                    WHERE commission_rule_id=? AND policy_version_id=?
                    """, values.toArray());
            requireChanged(changed);
        }
    }

    private void saveFeeRule(Long policyId, PolicyDraft.FeeRegimeRuleDraft rule, OffsetDateTime createdAt) {
        boolean restoreIdentity = createdAt != null;
        List<Object> values = new ArrayList<>();
        values.add(rule.feeRegimeCode());
        values.add(rule.salesStartDateFrom());
        values.add(rule.salesStartDateTo());
        values.add(rule.basicDocumentVersion());
        values.add(rule.basicDocumentDateFrom());
        values.add(rule.basicDocumentDateTo());
        values.add(rule.maxInstallments());
        values.add(rule.longTermMaintenanceFrom());
        values.add(rule.longTermMaintenanceTo());
        values.add(rule.reviewRequiredYn());
        values.add(json(rule.sourceRefs().stream().distinct().toList()));
        if (rule.feeRegimeRulesetId() == null || restoreIdentity) {
            values.add(policyId);
            if (restoreIdentity) {
                values.add(rule.feeRegimeRulesetId());
                values.add(createdAt);
            }
            if (restoreIdentity) {
                jdbc.update("""
                    INSERT INTO fgc.fee_regime_ruleset
                      (fee_regime_code, sales_start_date_from, sales_start_date_to, basic_document_version,
                       basic_document_date_from, basic_document_date_to, max_installments,
                       long_term_maintenance_from, long_term_maintenance_to, review_required_yn, source_refs, policy_version_id,
                       fee_regime_ruleset_id, created_at)
                    OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ARRAY(SELECT jsonb_array_elements_text(?::jsonb)), ?, ?, ?)
                    """, values.toArray());
            } else {
                jdbc.update("""
                    INSERT INTO fgc.fee_regime_ruleset
                      (fee_regime_code, sales_start_date_from, sales_start_date_to, basic_document_version,
                       basic_document_date_from, basic_document_date_to, max_installments,
                       long_term_maintenance_from, long_term_maintenance_to, review_required_yn, source_refs, policy_version_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ARRAY(SELECT jsonb_array_elements_text(?::jsonb)), ?)
                    """, values.toArray());
            }
        } else {
            values.add(rule.feeRegimeRulesetId());
            values.add(policyId);
            int changed = jdbc.update("""
                    UPDATE fgc.fee_regime_ruleset SET fee_regime_code=?, sales_start_date_from=?, sales_start_date_to=?,
                      basic_document_version=?, basic_document_date_from=?, basic_document_date_to=?, max_installments=?,
                      long_term_maintenance_from=?, long_term_maintenance_to=?, review_required_yn=?,
                      source_refs=ARRAY(SELECT jsonb_array_elements_text(?::jsonb))
                    WHERE fee_regime_ruleset_id=? AND policy_version_id=?
                    """, values.toArray());
            requireChanged(changed);
        }
    }

    private static void requireChanged(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("Policy child was not owned by the locked policy");
        }
    }

    public PolicyDraft readDraft(PolicyVersion policy) {
        Long id = policy.getPolicyVersionId();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("policyCode", policy.getPolicyCode());
        document.put("policyName", policy.getPolicyName());
        document.put("policyType", policy.getPolicyType());
        document.put("sourceClass", policy.getSourceClass());
        document.put("effectiveFrom", policy.getEffectiveFrom());
        document.put("effectiveTo", policy.getEffectiveTo());
        document.put("feeRegimeCode", policy.getFeeRegimeCode());
        document.put("basicDocumentVersion", policy.getBasicDocumentVersion());
        document.put("regulationRefs", policy.getRegulationRefs());
        document.put("sourceRefs", policy.getSourceRefs());
        document.put("expectedUpdatedAt", policy.getUpdatedAt());
        document.put("sourceDetails", rows("SELECT ref, title, explanation, url FROM fgc.policy_source_detail WHERE policy_version_id = ? ORDER BY ref", id));
        document.put("commissionRules", rows("""
                SELECT commission_rule_id, payment_stage, insurer_id, product_offering_id, organization_id,
                       agent_rank_code, commission_item_id, fee_component_type, installment_from, installment_to,
                       calculation_type, basis_code, rate_pct, fixed_amount, equal_monthly_yn, payment_condition_code, priority_no
                FROM fgc.commission_rule WHERE policy_version_id = ? ORDER BY commission_rule_id
                """, id));
        document.put("feeRegimeRules", rows("""
                SELECT fee_regime_ruleset_id, fee_regime_code, sales_start_date_from, sales_start_date_to,
                       basic_document_version, basic_document_date_from, basic_document_date_to, max_installments,
                       long_term_maintenance_from, long_term_maintenance_to, review_required_yn, source_refs
                FROM fgc.fee_regime_ruleset WHERE policy_version_id = ? ORDER BY fee_regime_ruleset_id
                """, id));
        document.put("parameters", rows("""
                SELECT parameter_key AS key, parameter_value AS value, description
                FROM fgc.policy_parameter WHERE policy_version_id = ? ORDER BY parameter_key
                """, id));
        document.put("clawbackRules", rows("""
                SELECT insurer_id, product_offering_id, commission_item_id, elapsed_month_from, elapsed_month_to,
                       clawback_basis_code, clawback_rate_pct, condition_expression
                FROM fgc.clawback_rule WHERE policy_version_id = ? ORDER BY clawback_rule_id
                """, id));
        return objectMapper.convertValue(document, PolicyDraft.class);
    }

    private List<Map<String, Object>> rows(String sql, Long id) {
        return jdbc.query(sql, (rs, row) -> rowMap(rs), id);
    }

    private Map<String, Object> rowMap(ResultSet rs) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 1; index <= rs.getMetaData().getColumnCount(); index++) {
            Object value = rs.getObject(index);
            if (value instanceof java.sql.Array array) {
                value = List.of((String[]) array.getArray());
                array.free();
            } else if (value instanceof java.sql.Date date) {
                value = date.toLocalDate();
            } else if (value != null && "jsonb".equals(rs.getMetaData().getColumnTypeName(index))) {
                try {
                    value = objectMapper.readTree(value.toString());
                } catch (JsonProcessingException exception) {
                    throw new SQLException("Invalid stored JSON", exception);
                }
            }
            result.put(camelCase(rs.getMetaData().getColumnLabel(index)), value);
        }
        return result;
    }

    private static String camelCase(String column) {
        StringBuilder value = new StringBuilder();
        boolean uppercase = false;
        for (char character : column.toCharArray()) {
            if (character == '_') {
                uppercase = true;
            } else {
                value.append(uppercase ? Character.toUpperCase(character) : character);
                uppercase = false;
            }
        }
        return value.toString();
    }

    public boolean hasUnsupportedChildren(Long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM fgc.cap_rule_set WHERE policy_version_id = ?)
                    OR EXISTS (SELECT 1 FROM fgc.refund_rate_table WHERE policy_version_id = ?)
                    OR EXISTS (SELECT 1 FROM fgc.allocation_policy WHERE policy_version_id = ?)
                    OR EXISTS (SELECT 1 FROM fgc.clawback_rule WHERE policy_version_id = ?)
                """, Boolean.class, id, id, id, id));
    }

    /** 승인 자식은 원본 값을 읽어 새 ID로 복사하며, 승인 이력은 복사하지 않는다. */
    public void copyChildren(Long sourceId, Long targetId) {
        entityManager.flush();
        jdbc.update("""
                INSERT INTO fgc.commission_rule
                    (policy_version_id, payment_stage, insurer_id, product_offering_id, organization_id,
                     agent_rank_code, commission_item_id, fee_component_type, installment_from, installment_to,
                     calculation_type, basis_code, rate_pct, fixed_amount, rounding_scale, rounding_mode,
                     equal_monthly_yn, payment_condition_code, priority_no, rule_expression)
                SELECT ?, payment_stage, insurer_id, product_offering_id, organization_id,
                     agent_rank_code, commission_item_id, fee_component_type, installment_from, installment_to,
                     calculation_type, basis_code, rate_pct, fixed_amount, rounding_scale, rounding_mode,
                     equal_monthly_yn, payment_condition_code, priority_no, rule_expression
                FROM fgc.commission_rule WHERE policy_version_id = ?
                """, targetId, sourceId);
        jdbc.update("""
                INSERT INTO fgc.policy_parameter (policy_version_id, parameter_key, parameter_value, description)
                SELECT ?, parameter_key, parameter_value, description FROM fgc.policy_parameter WHERE policy_version_id = ?
                """, targetId, sourceId);
        jdbc.update("""
                INSERT INTO fgc.policy_source_detail (policy_version_id, ref, title, explanation, url)
                SELECT ?, ref, title, explanation, url FROM fgc.policy_source_detail WHERE policy_version_id = ?
                """, targetId, sourceId);
        for (Long oldFeeId : jdbc.queryForList("SELECT fee_regime_ruleset_id FROM fgc.fee_regime_ruleset WHERE policy_version_id = ? ORDER BY fee_regime_ruleset_id", Long.class, sourceId)) {
            Long newFeeId = jdbc.queryForObject("""
                    INSERT INTO fgc.fee_regime_ruleset
                      (policy_version_id, fee_regime_code, sales_start_date_from, sales_start_date_to,
                       basic_document_version, basic_document_date_from, basic_document_date_to, max_installments,
                       long_term_maintenance_from, long_term_maintenance_to, review_required_yn, source_refs)
                    SELECT ?, fee_regime_code, sales_start_date_from, sales_start_date_to,
                       basic_document_version, basic_document_date_from, basic_document_date_to, max_installments,
                       long_term_maintenance_from, long_term_maintenance_to, review_required_yn, source_refs
                    FROM fgc.fee_regime_ruleset WHERE fee_regime_ruleset_id = ? RETURNING fee_regime_ruleset_id
                    """, Long.class, targetId, oldFeeId);
            jdbc.update("""
                    INSERT INTO fgc.fee_regime_ruleset_limit
                        (fee_regime_ruleset_id, limit_code, limit_pct, includes_indirect_expense_yn)
                    SELECT ?, limit_code, limit_pct, includes_indirect_expense_yn
                    FROM fgc.fee_regime_ruleset_limit WHERE fee_regime_ruleset_id = ?
                    """, newFeeId, oldFeeId);
        }
    }

    /** 같은 불변 버전의 수정·제출 참여자는 반려를 거쳐도 그 버전을 직접 승인할 수 없다. */
    public boolean hasContributed(Long id, Long actorId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM fgc.audit_log
                    WHERE entity_type = 'POLICY_VERSION' AND entity_id = ? AND user_id = ?
                      AND action_code IN ('POLICY_CREATE', 'POLICY_UPDATE', 'POLICY_CLONE')
                ) OR EXISTS (
                    SELECT 1 FROM fgc.policy_approval_action
                    WHERE policy_version_id = ? AND action_by = ? AND action_type = 'SUBMIT'
                )
                """, Boolean.class, id.toString(), actorId, id, actorId));
    }

    public void appendAction(Long id, String actionType, Long actorId, String reason, String evidenceRef) {
        jdbc.update("""
                INSERT INTO fgc.policy_approval_action (policy_version_id, action_seq, action_type, action_by, comment, evidence_ref)
                SELECT ?, COALESCE(MAX(action_seq), 0) + 1, ?, ?, ?, ?
                FROM fgc.policy_approval_action WHERE policy_version_id = ?
                """, id, actionType, actorId, reason, evidenceRef, id);
    }

    public void recordSuccession(PolicyVersion previous, PolicyVersion successor, Long actorId) {
        jdbc.update("""
                INSERT INTO fgc.policy_succession
                    (previous_policy_version_id, successor_policy_version_id, cutover_date, activated_by)
                VALUES (?, ?, ?, ?)
                """, previous.getPolicyVersionId(), successor.getPolicyVersionId(), successor.getEffectiveFrom(), actorId);
    }

    public PolicyApplicationPeriod applicationPeriod(Long id) {
        return jdbc.query("""
                SELECT p.effective_from, p.application_to, s.successor_policy_version_id
                FROM fgc.policy_application_version p
                LEFT JOIN fgc.policy_succession s ON s.previous_policy_version_id = p.policy_version_id
                WHERE p.policy_version_id = ?
                """, (rs, row) -> new PolicyApplicationPeriod(rs.getObject(1, java.time.LocalDate.class),
                rs.getObject(2, java.time.LocalDate.class), rs.getObject(3, Long.class)), id).stream().findFirst().orElse(null);
    }

    public List<PolicyApprovalActionResponse> findApprovalActions(Long id) {
        return jdbc.query("""
                SELECT a.policy_approval_action_id, a.action_seq, a.action_type, u.login_id,
                       a.action_at, a.comment, a.evidence_ref
                FROM fgc.policy_approval_action a JOIN fgc.app_user u ON u.user_id = a.action_by
                WHERE a.policy_version_id = ? ORDER BY a.action_seq
                """, (rs, row) -> new PolicyApprovalActionResponse(rs.getLong(1), rs.getInt(2), rs.getString(3),
                rs.getString(4), rs.getObject(5, OffsetDateTime.class), rs.getString(6), rs.getString(7)), id);
    }

    /** 승인자·승인시각이 있는 기존 승인본에서 실제 사용된 참조만 승인된 등록부로 사용한다. */
    public Set<String> approvedReferences() {
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT unnest(regulation_refs || source_refs)
                FROM fgc.policy_version
                WHERE status IN ('APPROVED','ACTIVE','RETIRED') AND approved_by IS NOT NULL AND approved_at IS NOT NULL
                UNION
                SELECT DISTINCT unnest(r.source_refs)
                FROM fgc.fee_regime_ruleset r JOIN fgc.policy_version p ON p.policy_version_id = r.policy_version_id
                WHERE p.status IN ('APPROVED','ACTIVE','RETIRED') AND p.approved_by IS NOT NULL AND p.approved_at IS NOT NULL
                """, String.class));
    }

    private static final String COMMISSION_SCOPE_OVERLAP = """
            EXISTS (
              SELECT 1 FROM fgc.commission_rule a JOIN fgc.commission_rule c
                ON c.policy_version_id = candidate.policy_version_id
              WHERE a.policy_version_id = active.policy_version_id
                AND a.payment_stage = c.payment_stage
                AND (a.insurer_id IS NULL OR c.insurer_id IS NULL OR a.insurer_id = c.insurer_id)
                AND (a.product_offering_id IS NULL OR c.product_offering_id IS NULL
                     OR a.product_offering_id = c.product_offering_id)
                AND (a.organization_id IS NULL OR c.organization_id IS NULL
                     OR a.organization_id = c.organization_id))
            """;

    public boolean hasActiveOverlap(PolicyVersion policy, boolean excludeSameCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM fgc.policy_application_version active
                  JOIN fgc.policy_version candidate ON candidate.policy_version_id = ?
                  WHERE active.policy_version_id <> candidate.policy_version_id
                    AND (NOT ? OR active.policy_code <> candidate.policy_code OR active.status = 'RETIRED')
                    AND daterange(active.effective_from, active.application_to, '[]')
                        && daterange(candidate.effective_from, candidate.effective_to, '[]')
                    AND (
                      active.policy_code = candidate.policy_code
                      OR (active.policy_type = candidate.policy_type
                        AND candidate.policy_type IN ('ARBITRAGE','SCHEDULE_ELIGIBILITY','RECONCILIATION_TOLERANCE'))
                      OR (active.policy_type = 'CURRENT_COMMISSION' AND candidate.policy_type = 'CURRENT_COMMISSION'
                        AND COALESCE(active.fee_regime_code, 'CURRENT') = 'CURRENT'
                        AND COALESCE(candidate.fee_regime_code, 'CURRENT') = 'CURRENT'
                        AND (active.basic_document_version IS NULL OR candidate.basic_document_version IS NULL
                             OR active.basic_document_version = candidate.basic_document_version)
                        AND %s)
                      OR EXISTS (
                        SELECT 1 FROM fgc.fee_regime_ruleset a JOIN fgc.fee_regime_ruleset c
                          ON c.policy_version_id = candidate.policy_version_id
                        WHERE a.policy_version_id = active.policy_version_id
                          AND active.policy_type = candidate.policy_type
                          AND a.fee_regime_code = c.fee_regime_code
                          AND (COALESCE(active.basic_document_version, a.basic_document_version) IS NULL
                               OR COALESCE(candidate.basic_document_version, c.basic_document_version) IS NULL
                               OR COALESCE(active.basic_document_version, a.basic_document_version)
                                  = COALESCE(candidate.basic_document_version, c.basic_document_version))
                          AND daterange(a.sales_start_date_from, a.sales_start_date_to, '[]')
                              && daterange(c.sales_start_date_from, c.sales_start_date_to, '[]')
                          AND daterange(a.basic_document_date_from, a.basic_document_date_to, '[]')
                              && daterange(c.basic_document_date_from, c.basic_document_date_to, '[]')
                          AND (%s OR (
                            NOT EXISTS (SELECT 1 FROM fgc.commission_rule WHERE policy_version_id = active.policy_version_id)
                            AND NOT EXISTS (SELECT 1 FROM fgc.commission_rule WHERE policy_version_id = candidate.policy_version_id))))
                    )
                )
                """.formatted(COMMISSION_SCOPE_OVERLAP, COMMISSION_SCOPE_OVERLAP),
                Boolean.class, policy.getPolicyVersionId(), excludeSameCode));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Policy persistence JSON serialization failed", exception);
        }
    }
}
