package com.susukkang.fgc.contract.repository;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.contract.dto.ContractDetailResponse;
import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.dto.ContractStatusEventProcessingRow;
import com.susukkang.fgc.contract.dto.ContractView;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import static com.susukkang.fgc.contract.entity.QInsuranceContract.insuranceContract;
import static com.susukkang.fgc.base.entity.QInsurer.insurer;
import static com.susukkang.fgc.base.entity.QProductOffering.productOffering;
import static com.susukkang.fgc.base.entity.QProduct.product;
import static com.susukkang.fgc.base.entity.QAgent.agent;
import static com.susukkang.fgc.cap.entity.QLatestCapCheck.latestCapCheck;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 설명 : 계약 검색·CSV·상세 화면용 조회와 등록·수정에 필요한 기준정보 검증을 담당한다.
 * 동적 검색은 QueryDSL, 고정 상세는 JPQL, PostgreSQL 금액 집계는 네이티브 SQL로 조회한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Repository
@RequiredArgsConstructor
public class ContractQueryRepository {

    private static final String CONTRACT_ID_PARAMETER = "contractId";

    private final EntityManager entityManager;
    private final JPAQueryFactory queryFactory;

    /** 계약 상세 화면에 필요한 기준정보를 함께 조회한다. */
    @Transactional(readOnly = true)
    public Optional<ContractDetailResponse> findDetailById(Long contractId) {
        if (contractId == null) {
            return Optional.empty();
        }
        return entityManager.createQuery("""
                SELECT new com.susukkang.fgc.contract.dto.ContractDetailResponse(
                    c.contractId, c.insurerId, i.insurerName, c.productOfferingId,
                    c.contractNo, p.productName, po.offeringVersion, c.contractDate,
                    c.agentId, a.agentName, c.organizationId, o.organizationName,
                    c.premiumPerCycleAmount, c.paymentCycleCode, c.firstPremiumAmount,
                    c.monthlyEquivalentFirstPremium, c.paymentTermMonths,
                    c.standardSurrenderDeductionAmount, c.currentStatus, c.dataOrigin, c.updatedAt)
                  FROM InsuranceContract c
                  JOIN Insurer i ON i.insurerId = c.insurerId
                  JOIN ProductOffering po ON po.productOfferingId = c.productOfferingId
                  JOIN Product p ON p.productId = po.productId
                  JOIN Agent a ON a.agentId = c.agentId
                  JOIN Organization o ON o.organizationId = c.organizationId
                 WHERE c.contractId = :contractId
                """, ContractDetailResponse.class)
                .setParameter(CONTRACT_ID_PARAMETER, contractId).getResultList().stream().findFirst();
    }

    /** 사건 원본이 아닌 Job별 처리 테이블에서 처리 이력을 조회한다. */
    @Transactional(readOnly = true)
    public List<ContractStatusEventProcessingRow> findStatusEventProcessingsByContractId(Long contractId) {
        return entityManager.createQuery("""
                SELECT new com.susukkang.fgc.contract.dto.ContractStatusEventProcessingRow(
                    p.contractStatusEventId, p.processingJob, p.processingStatus, p.processedAt)
                  FROM ContractStatusEventProcessing p
                  JOIN ContractStatusEvent e ON e.contractStatusEventId = p.contractStatusEventId
                 WHERE e.contractId = :contractId
                 ORDER BY p.contractStatusEventId, p.processedAt, p.contractStatusEventProcessingId
                """, ContractStatusEventProcessingRow.class)
                .setParameter(CONTRACT_ID_PARAMETER, contractId).getResultList();
    }

    @Transactional(readOnly = true)
    public boolean existsActiveInsurer(Long insurerId) {
        if (insurerId == null) {
            return false;
        }
        return exists("""
                SELECT COUNT(i) FROM Insurer i WHERE i.insurerId = :insurerId AND i.activeYn = TRUE
                """, Map.of("insurerId", insurerId));
    }

    @Transactional(readOnly = true)
    public boolean existsValidProductOffering(Long insurerId, Long productOfferingId, LocalDate contractDate) {
        if (insurerId == null || productOfferingId == null || contractDate == null) {
            return false;
        }
        return exists("""
                SELECT COUNT(po) FROM ProductOffering po
                    JOIN Product p ON p.productId = po.productId
                     WHERE p.insurerId = :insurerId AND po.productOfferingId = :productOfferingId
                       AND p.activeYn = TRUE AND po.activeYn = TRUE
                       AND po.salesStartDate <= :contractDate
                       AND (po.salesEndDate IS NULL OR po.salesEndDate >= :contractDate)
                """, Map.of("insurerId", insurerId, "productOfferingId", productOfferingId,
                "contractDate", contractDate));
    }

    @Transactional(readOnly = true)
    public boolean existsEligibleAgent(Long agentId, LocalDate contractDate) {
        if (agentId == null || contractDate == null) {
            return false;
        }
        return exists("""
                SELECT COUNT(a) FROM Agent a
                     WHERE a.agentId = :agentId AND a.rankCode = com.susukkang.fgc.common.code.AgentRankCode.FC
                       AND a.activeYn = TRUE AND a.agentStatus = 'ACTIVE'
                       AND a.appointmentDate <= :contractDate
                       AND (a.terminationDate IS NULL OR a.terminationDate >= :contractDate)
                """, Map.of("agentId", agentId, "contractDate", contractDate));
    }

    @Transactional(readOnly = true)
    public boolean existsValidAgentOrganization(Long agentId, Long organizationId, LocalDate contractDate) {
        if (agentId == null || organizationId == null || contractDate == null) {
            return false;
        }
        return exists("""
                SELECT COUNT(a) FROM Agent a JOIN Organization o ON o.organizationId = a.organizationId
                     WHERE a.agentId = :agentId AND a.organizationId = :organizationId
                       AND o.activeYn = TRUE AND o.effectiveFrom <= :contractDate
                       AND (o.effectiveTo IS NULL OR o.effectiveTo >= :contractDate)
                """, Map.of("agentId", agentId, "organizationId", organizationId,
                "contractDate", contractDate));
    }

    /** 계약 첫 12개월의 확정 지급 중 증빙이 있는 준법경영비 귀속액을 합산한다. */
    @Transactional(readOnly = true)
    public BigDecimal findComplianceEvidenceAmount(Long contractId, PaymentStage paymentStage) {
        NativeQuery<?> query = nativeQuery("""
                SELECT SUM(ROUND(ta.attributed_amount, 0)) AS evidence_amount
                  FROM fgc.transaction_attribution ta
                  JOIN fgc.commission_transaction ct
                    ON ct.commission_transaction_id = ta.commission_transaction_id
                  JOIN fgc.insurance_contract c ON c.contract_id = ta.contract_id
                 WHERE ta.contract_id = :contractId
                   AND ta.exclusion_type_snapshot = 'COMPLIANCE_3PCT'
                   AND NULLIF(BTRIM(ta.evidence_ref), '') IS NOT NULL
                   AND ct.payment_stage = :paymentStage AND ct.status = 'CONFIRMED'
                   AND ct.cashflow_type = 'PAYMENT'
                   AND ta.attribution_date >= c.contract_date
                   AND ta.attribution_date < c.contract_date + INTERVAL '12 months'
                """);
        query.setParameter(CONTRACT_ID_PARAMETER, contractId);
        query.setParameter("paymentStage", paymentStage.name());
        query.addScalar("evidence_amount", BigDecimal.class);
        return (BigDecimal) query.getSingleResult();
    }

    private boolean exists(String sql, Map<String, Object> parameters) {
        var query = entityManager.createQuery(sql, Long.class);
        parameters.forEach(query::setParameter);
        return query.getSingleResult() > 0;
    }

    private NativeQuery<?> nativeQuery(String sql) {
        return entityManager.createNativeQuery(sql).unwrap(NativeQuery.class);
    }


    /** 목록·CSV·건수에 동일한 조건과 조인을 적용한다. 최신 판정은 기존 뷰를 그대로 사용한다. */
    @Transactional(readOnly = true)
    public Page<ContractView> search(ContractSearchCondition condition, Pageable pageable) {
        List<ContractView> content = contentQuery(condition)
                .offset(pageable.getOffset()).limit(pageable.getPageSize()).fetch();
        long total = joins(queryFactory.select(insuranceContract.count()).from(insuranceContract))
                .where(filters(condition)).fetchOne();
        return new PageImpl<>(content, pageable, total);
    }

    @Transactional(readOnly = true)
    public List<ContractView> searchAll(ContractSearchCondition condition) {
        return contentQuery(condition).fetch();
    }

    private JPAQuery<ContractView> contentQuery(ContractSearchCondition condition) {
        var c = insuranceContract;
        return joins(queryFactory.select(Projections.constructor(ContractView.class,
                        c.contractId, c.contractNo, insurer.insurerName, product.productName,
                        c.contractDate, c.monthlyEquivalentFirstPremium,
                        Expressions.stringTemplate("concat('FC-', lpad(cast({0} as string), 4, '0'), ' ', {1})",
                                agent.agentId, agent.agentName),
                        c.currentStatus, latestCapCheck.resultStatus, c.dataOrigin)).from(c))
                .where(filters(condition)).orderBy(c.contractId.desc());
    }

    private <T> JPAQuery<T> joins(JPAQuery<T> query) {
        var c = insuranceContract;
        return query.join(insurer).on(insurer.insurerId.eq(c.insurerId))
                .join(productOffering).on(productOffering.productOfferingId.eq(c.productOfferingId))
                .join(product).on(product.productId.eq(productOffering.productId))
                .join(agent).on(agent.agentId.eq(c.agentId))
                .leftJoin(latestCapCheck).on(latestCapCheck.contractId.eq(c.contractId)
                        .and(latestCapCheck.paymentStage.eq(PaymentStage.GA_TO_FC)));
    }

    private BooleanBuilder filters(ContractSearchCondition condition) {
        BooleanBuilder where = new BooleanBuilder();
        if (condition == null) return where;
        var c = insuranceContract;
        // LIKE의 %, _와 PostgreSQL 기본 역슬래시 이스케이프 의미를 기존 그대로 보존한다.
        if (condition.getContractNo() != null && !condition.getContractNo().isEmpty())
            where.and(c.contractNo.like("%" + condition.getContractNo() + "%", (char) 92));
        if (condition.getInsurerId() != null) where.and(c.insurerId.eq(condition.getInsurerId()));
        if (condition.getProductOfferingId() != null) where.and(c.productOfferingId.eq(condition.getProductOfferingId()));
        if (condition.getAgentId() != null) where.and(c.agentId.eq(condition.getAgentId()));
        if (condition.getOrgId() != null) where.and(c.organizationId.eq(condition.getOrgId()));
        if (condition.getCapResultStatus() != null) where.and(latestCapCheck.resultStatus.eq(condition.getCapResultStatus()));
        if (condition.getCurrentStatus() != null) where.and(c.currentStatus.eq(condition.getCurrentStatus()));
        if (condition.getContractDateFrom() != null) where.and(c.contractDate.goe(condition.getContractDateFrom()));
        if (condition.getContractDateTo() != null) where.and(c.contractDate.loe(condition.getContractDateTo()));
        return where;
    }
}
