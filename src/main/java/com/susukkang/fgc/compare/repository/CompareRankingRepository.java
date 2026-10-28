package com.susukkang.fgc.compare.repository;

import com.susukkang.fgc.compare.service.CompareRankingCalculator.CapRule;
import com.susukkang.fgc.compare.service.CompareRankingCalculator.Rule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    /** 환수 기준으로 쓰는 환급률(%)과 출처 정책 버전. */
    public record RefundRate(BigDecimal ratePct, String policyVersion) {
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

    /** 기준일에 유효한 GA→설계사 현행 수수료 규칙 중 이 상품·설계사에 적용되는 무조건 규칙. */
    @SuppressWarnings("unchecked")
    public List<Rule> findCommissionRules(Offering offering, AgentProfile agent, LocalDate asOf) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT r.installment_from, r.installment_to, r.rate_pct, r.fixed_amount, ci.item_name,
                       pv.policy_code || ' v' || pv.version_no
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
                ORDER BY r.installment_from, r.priority_no, r.commission_rule_id
                """)
                .setParameter("offeringId", offering.offeringId()).setParameter("insurerId", offering.insurerId())
                .setParameter("asOf", asOf).setParameter("orgId", agent.organizationId())
                .setParameter("rank", agent.rankCode()).getResultList();
        return rows.stream().map(r -> new Rule(((Number) r[0]).intValue(), ((Number) r[1]).intValue(),
                (BigDecimal) r[2], (BigDecimal) r[3], (String) r[4], (String) r[5])).toList();
    }

    /**
     * 상품의 해약환급률표에서 지정 차월 환급률(%)을 읽는다. 표가 없으면 비어 있다.
     * 환수 규칙(clawback_rule)이 구현되기 전까지 환수 노출액의 환수율로 임시 사용한다. 환수 규칙 구현 후 이 조회를 환수 규칙 조회로 바꾼다.
     */
    @SuppressWarnings("unchecked")
    public Optional<RefundRate> findRefundRate(Offering offering, int contractMonth, LocalDate asOf) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT l.refund_rate_pct, pv.policy_code || ' v' || pv.version_no FROM fgc.refund_rate_table t
                JOIN fgc.policy_version pv ON pv.policy_version_id = t.policy_version_id AND pv.status = 'ACTIVE'
                JOIN fgc.refund_rate_line l ON l.refund_rate_table_id = t.refund_rate_table_id AND l.contract_month_no = :month
                WHERE t.product_id = :productId AND (t.product_offering_id IS NULL OR t.product_offering_id = :offeringId)
                  AND t.effective_from <= :asOf AND (t.effective_to IS NULL OR t.effective_to >= :asOf)
                ORDER BY (t.product_offering_id IS NULL), t.effective_from DESC, t.refund_rate_table_id DESC
                LIMIT 1
                """).setParameter("productId", offering.productId()).setParameter("offeringId", offering.offeringId())
                .setParameter("month", contractMonth).setParameter("asOf", asOf).getResultList();
        return rows.stream().findFirst().map(r -> new RefundRate((BigDecimal) r[0], (String) r[1]));
    }

    /** 기준일에 유효한 GA→설계사 한도 규칙의 한도 배수. 상품군·보험사 지정 규칙이 공통 규칙보다 우선한다. */
    @SuppressWarnings("unchecked")
    public Optional<CapRule> findCapRule(Offering offering, LocalDate asOf) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT s.premium_multiplier, pv.policy_code || ' v' || pv.version_no
                FROM fgc.cap_rule_set s JOIN fgc.policy_version pv ON pv.policy_version_id = s.policy_version_id
                WHERE pv.status = 'ACTIVE' AND s.payment_stage = 'GA_TO_FC'
                  AND s.contract_date_from <= :asOf AND (s.contract_date_to IS NULL OR s.contract_date_to >= :asOf)
                  AND (s.insurer_id IS NULL OR s.insurer_id = :insurerId)
                  AND (s.product_group_code IS NULL OR s.product_group_code = :groupCode)
                ORDER BY (s.insurer_id IS NULL), (s.product_group_code IS NULL), s.contract_date_from DESC, s.cap_rule_set_id DESC
                LIMIT 1
                """).setParameter("asOf", asOf).setParameter("insurerId", offering.insurerId())
                .setParameter("groupCode", offering.groupCode()).getResultList();
        return rows.stream().findFirst().map(r -> new CapRule((BigDecimal) r[0], (String) r[1]));
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
