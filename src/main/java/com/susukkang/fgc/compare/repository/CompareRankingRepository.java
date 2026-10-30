package com.susukkang.fgc.compare.repository;

import com.susukkang.fgc.compare.service.ClawbackRuleResolver;
import com.susukkang.fgc.compare.service.CompareRankingCalculator.CapRule;
import com.susukkang.fgc.compare.service.CommissionRuleResolver.RuleRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 설명 : 상품 비교 순위에 쓰는 조회 전용 저장소. 정책값은 코드가 아니라 정책 테이블에서 읽는다.
 */
@Repository
public class CompareRankingRepository {

    public record Offering(long offeringId, long insurerId, String insurer, long productId, String product, String groupCode) {
    }

    public record AgentProfile(Long organizationId, String rankCode) {
    }

    public record CapRow(Double usagePct, String status, int contractCount) {
    }

    @PersistenceContext
    private EntityManager em;

    /** 기준일에 판매 중인 승인 상품 판매버전을 상품군 코드와 함께 돌려준다. */
    @SuppressWarnings("unchecked")
    public List<Offering> findSellableOfferings(LocalDate asOf) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT po.product_offering_id, i.insurer_id, i.insurer_name, p.product_id, p.product_name, p.product_group_code
                FROM fgc.product_offering po
                JOIN fgc.product p ON p.product_id = po.product_id
                JOIN fgc.insurer i ON i.insurer_id = p.insurer_id
                WHERE po.active_yn AND po.governance_status = 'APPROVED'
                  AND po.sales_start_date <= :asOf AND (po.sales_end_date IS NULL OR po.sales_end_date >= :asOf)
                ORDER BY i.insurer_name, p.product_name, po.product_offering_id
                """).setParameter("asOf", asOf).getResultList();
        return rows.stream().map(r -> new Offering(((Number) r[0]).longValue(), ((Number) r[1]).longValue(), (String) r[2],
                ((Number) r[3]).longValue(), (String) r[4], (String) r[5])).toList();
    }

    @SuppressWarnings("unchecked")
    public Optional<AgentProfile> findAgent(long agentId) {
        List<Object[]> rows = em.createNativeQuery("SELECT organization_id, rank_code FROM fgc.agent WHERE agent_id = :id")
                .setParameter("id", agentId).getResultList();
        return rows.stream().findFirst().map(r -> new AgentProfile(((Number) r[0]).longValue(), (String) r[1]));
    }

    /** 설계사 본인 유효 계약의 평균 월 환산 초회보험료(원, HALF_UP). 계약이 없으면 비어 있다. */
    public Optional<Long> averageMonthlyEquivalentPremium(long agentId) {
        Object value = em.createNativeQuery("""
                SELECT round(avg(monthly_equivalent_first_premium)) FROM fgc.insurance_contract
                WHERE agent_id = :id AND current_status NOT IN ('CANCELLED','TERMINATED')
                """).setParameter("id", agentId).getSingleResult();
        return value == null ? Optional.empty() : Optional.of(((Number) value).longValue());
    }

    /** 기준일에 유효한 GA→설계사 현행 수수료 규칙 중 이 상품·설계사에 맞는 무조건 규칙 전부. 1건 선택은 CommissionRuleResolver 가 한다. */
    @SuppressWarnings("unchecked")
    public List<RuleRow> findCommissionRules(Offering offering, AgentProfile agent, LocalDate asOf) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT r.commission_rule_id, r.policy_version_id, r.commission_item_id, r.installment_from, r.installment_to,
                       r.rate_pct, r.fixed_amount, ci.item_name, pv.policy_code || ' v' || pv.version_no,
                       r.product_offering_id IS NOT NULL, r.insurer_id IS NOT NULL, r.agent_rank_code IS NOT NULL,
                       r.organization_id IS NOT NULL, r.priority_no
                FROM fgc.commission_rule r
                JOIN fgc.policy_version pv ON pv.policy_version_id = r.policy_version_id
                JOIN fgc.commission_item ci ON ci.commission_item_id = r.commission_item_id
                JOIN fgc.product_offering po ON po.product_offering_id = :offeringId
                WHERE pv.policy_type = 'CURRENT_COMMISSION' AND pv.status = 'ACTIVE'
                  AND pv.effective_from <= :asOf AND (pv.effective_to IS NULL OR pv.effective_to >= :asOf)
                  AND (pv.fee_regime_code IS NULL OR pv.fee_regime_code = po.fee_regime_code)
                  AND r.payment_stage = 'GA_TO_FC' AND r.payment_condition_code IS NULL
                  AND (r.insurer_id IS NULL OR r.insurer_id = :insurerId)
                  AND (r.product_offering_id IS NULL OR r.product_offering_id = :offeringId)
                  AND (r.organization_id IS NULL OR r.organization_id = :orgId)
                  AND (r.agent_rank_code IS NULL OR r.agent_rank_code = :rank)
                ORDER BY r.commission_rule_id
                """)
                .setParameter("offeringId", offering.offeringId()).setParameter("insurerId", offering.insurerId())
                .setParameter("asOf", asOf).setParameter("orgId", agent.organizationId())
                .setParameter("rank", agent.rankCode()).getResultList();
        return rows.stream().map(r -> new RuleRow(((Number) r[0]).longValue(), ((Number) r[1]).longValue(),
                ((Number) r[2]).longValue(), ((Number) r[3]).intValue(), ((Number) r[4]).intValue(), (BigDecimal) r[5],
                (BigDecimal) r[6], (String) r[7], (String) r[8], (Boolean) r[9], (Boolean) r[10], (Boolean) r[11],
                (Boolean) r[12], ((Number) r[13]).intValue())).toList();
    }

    /**
     * 기준일을 계약일로 볼 때 적용되는 승인 환수 정책(CLAWBACK)의 규칙 중 이 상품의 보험사·판매버전에 맞는 것 전부.
     * 정책 선택은 FUN-054 후보 조회와 같다(승인본 + 계약일 적용 구간). 항목·경과월 선택은 ClawbackRuleResolver 가 한다.
     */
    @SuppressWarnings("unchecked")
    public List<ClawbackRuleResolver.RuleRow> findClawbackRules(Offering offering, LocalDate asOf) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT r.policy_version_id, pv.policy_code || ' v' || pv.version_no, r.commission_item_id,
                       r.insurer_id IS NOT NULL, r.product_offering_id IS NOT NULL, r.elapsed_month_from, r.elapsed_month_to,
                       r.clawback_basis_code, r.clawback_rate_pct, r.condition_expression::text
                FROM fgc.policy_version pv
                JOIN fgc.policy_application_version app ON app.policy_version_id = pv.policy_version_id
                JOIN fgc.clawback_rule r ON r.policy_version_id = pv.policy_version_id
                WHERE pv.policy_type = 'CLAWBACK' AND pv.approved_at IS NOT NULL
                  AND app.effective_from <= :asOf AND (app.application_to IS NULL OR app.application_to >= :asOf)
                  AND (r.insurer_id IS NULL OR r.insurer_id = :insurerId)
                  AND (r.product_offering_id IS NULL OR r.product_offering_id = :offeringId)
                ORDER BY r.policy_version_id, r.clawback_rule_id
                """).setParameter("asOf", asOf).setParameter("insurerId", offering.insurerId())
                .setParameter("offeringId", offering.offeringId()).getResultList();
        return rows.stream().map(r -> new ClawbackRuleResolver.RuleRow(((Number) r[0]).longValue(), (String) r[1],
                r[2] == null ? null : ((Number) r[2]).longValue(), (Boolean) r[3], (Boolean) r[4], ((Number) r[5]).intValue(),
                ((Number) r[6]).intValue(), (String) r[7], (BigDecimal) r[8], (String) r[9])).toList();
    }

    /** 기준일에 유효한 GA→설계사 한도 규칙의 한도 배수. 상품군·보험사 지정 규칙이 공통 규칙보다 우선한다. */
    @SuppressWarnings("unchecked")
    public Optional<CapRule> findCapRule(Offering offering, LocalDate asOf) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT s.premium_multiplier, pv.policy_code || ' v' || pv.version_no, s.cap_rule_set_id
                FROM fgc.cap_rule_set s JOIN fgc.policy_version pv ON pv.policy_version_id = s.policy_version_id
                WHERE pv.status = 'ACTIVE' AND s.payment_stage = 'GA_TO_FC'
                  AND s.contract_date_from <= :asOf AND (s.contract_date_to IS NULL OR s.contract_date_to >= :asOf)
                  AND (s.insurer_id IS NULL OR s.insurer_id = :insurerId)
                  AND (s.product_group_code IS NULL OR s.product_group_code = :groupCode)
                ORDER BY (s.insurer_id IS NULL), (s.product_group_code IS NULL), s.contract_date_from DESC, s.cap_rule_set_id DESC
                LIMIT 1
                """).setParameter("asOf", asOf).setParameter("insurerId", offering.insurerId())
                .setParameter("groupCode", offering.groupCode()).getResultList();
        return rows.stream().findFirst().map(r -> new CapRule((BigDecimal) r[0], (String) r[1], ((Number) r[2]).longValue()));
    }

    /** 한도 규칙 세트의 수수료 항목별 산입 구분(INCLUDED/EXCLUDED/REVIEW_REQUIRED). 목록에 없는 항목은 구분이 없는 것이다. */
    @SuppressWarnings("unchecked")
    public Map<Long, String> findCapItemInclusion(long capRuleSetId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT commission_item_id, inclusion_status FROM fgc.cap_rule_item WHERE cap_rule_set_id = :id
                """).setParameter("id", capRuleSetId).getResultList();
        Map<Long, String> result = new HashMap<>();
        rows.forEach(r -> result.put(((Number) r[0]).longValue(), (String) r[1]));
        return result;
    }

    /** 협회 등급(표시 전용). 가장 최근 기준일 값을 쓰고 자료가 없으면 비어 있다. */
    @SuppressWarnings("unchecked")
    public Optional<String> findAssociationGrade(long offeringId, LocalDate asOf) {
        List<Object> rows = em.createNativeQuery("""
                SELECT grade_code FROM fgc.commission_grade
                WHERE product_offering_id = :id AND data_as_of <= :asOf
                ORDER BY data_as_of DESC, commission_grade_id DESC LIMIT 1
                """).setParameter("id", offeringId).setParameter("asOf", asOf).getResultList();
        return rows.stream().findFirst().map(Object::toString);
    }

    /** 설계사 본인 계약의 계약별 최신 한도 검증 결과 중 가장 높은 사용률과 가장 나쁜 상태. */
    public CapRow findCapSummary(long agentId) {
        Object[] row = (Object[]) em.createNativeQuery("""
                WITH latest AS (
                  SELECT DISTINCT ON (cc.contract_id, cc.payment_stage) cc.usage_pct, cc.result_status
                  FROM fgc.cap_check cc JOIN fgc.insurance_contract c ON c.contract_id = cc.contract_id
                  WHERE c.agent_id = :id AND cc.result_status <> 'NOT_APPLICABLE'
                  ORDER BY cc.contract_id, cc.payment_stage, cc.checked_at DESC, cc.cap_check_id DESC)
                SELECT max(usage_pct), count(*),
                  CASE WHEN bool_or(result_status = 'VIOLATION') THEN 'VIOLATION'
                       WHEN bool_or(result_status = 'REVIEW_REQUIRED') THEN 'REVIEW_REQUIRED'
                       WHEN bool_or(result_status = 'WARNING') THEN 'WARNING'
                       WHEN count(*) > 0 THEN 'NORMAL' END
                FROM latest
                """).setParameter("id", agentId).getSingleResult();
        return new CapRow(row[0] == null ? null : ((Number) row[0]).doubleValue(), (String) row[2], ((Number) row[1]).intValue());
    }
}
