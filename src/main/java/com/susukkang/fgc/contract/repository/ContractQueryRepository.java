package com.susukkang.fgc.contract.repository;

import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.DataOrigin;
import com.susukkang.fgc.common.code.PaymentCycleCode;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.contract.dto.ContractDetailResponse;
import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.dto.ContractStatusEventProcessingRow;
import com.susukkang.fgc.contract.dto.ContractView;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 설명 : 계약 검색·CSV·상세 화면용 조회와 등록·수정에 필요한 기준정보 검증을 담당한다.
 * 여러 도메인의 테이블과 뷰를 결합하는 조회는 네이티브 SQL로 수행하고 DTO로 반환한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Repository
@RequiredArgsConstructor
public class ContractQueryRepository {

    private final EntityManager entityManager;

    /** 계약 상세 화면에 필요한 기준정보를 함께 조회한다. */
    @Transactional(readOnly = true)
    public Optional<ContractDetailResponse> findDetailById(Long contractId) {
        if (contractId == null) {
            return Optional.empty();
        }
        NativeQuery<?> query = nativeQuery("""
                SELECT ic.contract_id, ic.insurer_id, i.insurer_name,
                       ic.product_offering_id, ic.contract_no, p.product_name,
                       po.offering_version, ic.contract_date, ic.agent_id, a.agent_name,
                       ic.organization_id, o.organization_name, ic.premium_per_cycle_amount,
                       ic.payment_cycle_code, ic.first_premium_amount,
                       ic.monthly_equivalent_first_premium, ic.payment_term_months,
                       ic.standard_surrender_deduction_amount,
                       ic.current_status AS contract_status, ic.data_origin
                  FROM fgc.insurance_contract ic
                  JOIN fgc.insurer i ON i.insurer_id = ic.insurer_id
                  JOIN fgc.product_offering po ON po.product_offering_id = ic.product_offering_id
                  JOIN fgc.product p ON p.product_id = po.product_id
                  JOIN fgc.agent a ON a.agent_id = ic.agent_id
                  JOIN fgc.organization o ON o.organization_id = ic.organization_id
                 WHERE ic.contract_id = :contractId
                """);
        query.setParameter("contractId", contractId);
        query.addScalar("contract_id", Long.class);
        query.addScalar("insurer_id", Long.class);
        query.addScalar("insurer_name", String.class);
        query.addScalar("product_offering_id", Long.class);
        query.addScalar("contract_no", String.class);
        query.addScalar("product_name", String.class);
        query.addScalar("offering_version", String.class);
        query.addScalar("contract_date", LocalDate.class);
        query.addScalar("agent_id", Long.class);
        query.addScalar("agent_name", String.class);
        query.addScalar("organization_id", Long.class);
        query.addScalar("organization_name", String.class);
        query.addScalar("premium_per_cycle_amount", BigDecimal.class);
        query.addScalar("payment_cycle_code", String.class);
        query.addScalar("first_premium_amount", BigDecimal.class);
        query.addScalar("monthly_equivalent_first_premium", BigDecimal.class);
        query.addScalar("payment_term_months", Integer.class);
        query.addScalar("standard_surrender_deduction_amount", BigDecimal.class);
        query.addScalar("contract_status", String.class);
        query.addScalar("data_origin", String.class);

        return query.setTupleTransformer((row, aliases) -> ContractDetailResponse.builder()
                        .contractId((Long) row[0])
                        .insurerId((Long) row[1])
                        .insurerName((String) row[2])
                        .productOfferingId((Long) row[3])
                        .contractNo((String) row[4])
                        .productName((String) row[5])
                        .offeringVersion((String) row[6])
                        .contractDate((LocalDate) row[7])
                        .agentId((Long) row[8])
                        .agentName((String) row[9])
                        .organizationId((Long) row[10])
                        .organizationName((String) row[11])
                        .premiumPerCycleAmount((BigDecimal) row[12])
                        .paymentCycleCode(PaymentCycleCode.valueOf((String) row[13]))
                        .firstPremiumAmount((BigDecimal) row[14])
                        .monthlyEquivalentFirstPremium((BigDecimal) row[15])
                        .paymentTermMonths((Integer) row[16])
                        .standardSurrenderDeductionAmount((BigDecimal) row[17])
                        .contractStatus(ContractStatus.valueOf((String) row[18]))
                        .dataOrigin(DataOrigin.valueOf((String) row[19]))
                        .build())
                .getResultList().stream().findFirst();
    }

    /** 사건 원본이 아닌 Job별 처리 테이블에서 처리 이력을 조회한다. */
    @Transactional(readOnly = true)
    public List<ContractStatusEventProcessingRow> findStatusEventProcessingsByContractId(Long contractId) {
        NativeQuery<?> query = nativeQuery("""
                SELECT p.contract_status_event_id, p.processing_job,
                       p.processing_status, p.processed_at
                  FROM fgc.contract_status_event_processing p
                  JOIN fgc.contract_status_event e
                    ON e.contract_status_event_id = p.contract_status_event_id
                 WHERE e.contract_id = :contractId
                 ORDER BY p.contract_status_event_id ASC, p.processed_at ASC,
                          p.contract_status_event_processing_id ASC
                """);
        query.setParameter("contractId", contractId);
        query.addScalar("contract_status_event_id", Long.class);
        query.addScalar("processing_job", String.class);
        query.addScalar("processing_status", String.class);
        query.addScalar("processed_at", OffsetDateTime.class);
        return query.setTupleTransformer((row, aliases) -> new ContractStatusEventProcessingRow(
                (Long) row[0], (String) row[1], (String) row[2], (OffsetDateTime) row[3]
        )).getResultList();
    }

    @Transactional(readOnly = true)
    public boolean existsActiveInsurer(Long insurerId) {
        if (insurerId == null) {
            return false;
        }
        return exists("""
                SELECT EXISTS (
                    SELECT 1 FROM fgc.insurer
                     WHERE insurer_id = :insurerId AND active_yn = TRUE
                )
                """, Map.of("insurerId", insurerId));
    }

    @Transactional(readOnly = true)
    public boolean existsValidProductOffering(Long insurerId, Long productOfferingId, LocalDate contractDate) {
        if (insurerId == null || productOfferingId == null || contractDate == null) {
            return false;
        }
        return exists("""
                SELECT EXISTS (
                    SELECT 1 FROM fgc.product_offering po
                    JOIN fgc.product p ON p.product_id = po.product_id
                     WHERE p.insurer_id = :insurerId
                       AND po.product_offering_id = :productOfferingId
                       AND p.active_yn = TRUE AND po.active_yn = TRUE
                       AND po.sales_start_date <= :contractDate
                       AND (po.sales_end_date IS NULL OR po.sales_end_date >= :contractDate)
                )
                """, Map.of("insurerId", insurerId, "productOfferingId", productOfferingId,
                "contractDate", contractDate));
    }

    @Transactional(readOnly = true)
    public boolean existsEligibleAgent(Long agentId, LocalDate contractDate) {
        if (agentId == null || contractDate == null) {
            return false;
        }
        return exists("""
                SELECT EXISTS (
                    SELECT 1 FROM fgc.agent
                     WHERE agent_id = :agentId AND rank_code = 'FC'
                       AND active_yn = TRUE AND agent_status = 'ACTIVE'
                       AND appointment_date <= :contractDate
                       AND (termination_date IS NULL OR termination_date >= :contractDate)
                )
                """, Map.of("agentId", agentId, "contractDate", contractDate));
    }

    @Transactional(readOnly = true)
    public boolean existsValidAgentOrganization(Long agentId, Long organizationId, LocalDate contractDate) {
        if (agentId == null || organizationId == null || contractDate == null) {
            return false;
        }
        return exists("""
                SELECT EXISTS (
                    SELECT 1 FROM fgc.agent a
                    JOIN fgc.organization o ON o.organization_id = a.organization_id
                     WHERE a.agent_id = :agentId AND a.organization_id = :organizationId
                       AND o.active_yn = TRUE AND o.effective_from <= :contractDate
                       AND (o.effective_to IS NULL OR o.effective_to >= :contractDate)
                )
                """, Map.of("agentId", agentId, "organizationId", organizationId,
                "contractDate", contractDate));
    }

    /** 계약 첫 12개월의 확정 지급 중 증빙이 있는 준법경영비 귀속액을 합산한다. */
    @Transactional(readOnly = true)
    public BigDecimal findComplianceEvidenceAmount(Long contractId, PaymentStage paymentStage) {
        NativeQuery<?> query = nativeQuery("""
                SELECT SUM(CASE WHEN ct.cashflow_type = 'DEDUCTION'
                                THEN -ROUND(ta.attributed_amount, 0)
                                ELSE ROUND(ta.attributed_amount, 0) END) AS evidence_amount
                  FROM fgc.transaction_attribution ta
                  JOIN fgc.commission_transaction ct
                    ON ct.commission_transaction_id = ta.commission_transaction_id
                  JOIN fgc.insurance_contract c ON c.contract_id = ta.contract_id
                 WHERE ta.contract_id = :contractId
                   AND ta.exclusion_type_snapshot = 'COMPLIANCE_3PCT'
                   AND NULLIF(BTRIM(ta.evidence_ref), '') IS NOT NULL
                   AND ct.payment_stage = :paymentStage AND ct.status = 'CONFIRMED'
                   AND ta.attribution_date >= c.contract_date
                   AND ta.attribution_date < c.contract_date + INTERVAL '12 months'
                """);
        query.setParameter("contractId", contractId);
        query.setParameter("paymentStage", paymentStage.name());
        query.addScalar("evidence_amount", BigDecimal.class);
        return (BigDecimal) query.getSingleResult();
    }

    private boolean exists(String sql, Map<String, Object> parameters) {
        Query query = entityManager.createNativeQuery(sql);
        parameters.forEach(query::setParameter);
        return Boolean.TRUE.equals(query.getSingleResult());
    }

    private NativeQuery<?> nativeQuery(String sql) {
        return entityManager.createNativeQuery(sql).unwrap(NativeQuery.class);
    }

    private static final String SELECT_SQL = """
            SELECT
                ic.contract_id AS contract_id,
                ic.contract_no AS contract_no,
                i.insurer_name AS insurer_name,
                p.product_name AS product_name,
                ic.contract_date AS contract_date,
                ic.monthly_equivalent_first_premium
                    AS monthly_equivalent_first_premium,
                CONCAT(
                    'FC-',
                    LPAD(CAST(a.agent_id AS text), 4, '0'),
                    ' ',
                    a.agent_name
                ) AS agent_id_name,
                ic.current_status AS contract_status,
                cc.result_status AS cap_result_status,
                ic.data_origin AS data_origin
            """;

    private static final String FROM_SQL = """
            FROM fgc.insurance_contract ic
            JOIN fgc.insurer i
                ON i.insurer_id = ic.insurer_id
            JOIN fgc.product_offering po
                ON po.product_offering_id = ic.product_offering_id
            JOIN fgc.product p
                ON p.product_id = po.product_id
            JOIN fgc.agent a
                ON a.agent_id = ic.agent_id
            LEFT JOIN fgc.vw_latest_cap_check cc
                ON cc.contract_id = ic.contract_id
               AND cc.payment_stage = 'GA_TO_FC'
            """;

    // S2077 검토: buildWhere는 코드에 정의된 조건식만 조합하며 사용자 입력은 모두 setParameter로 바인딩한다.
    // ORDER BY도 고정이고 페이징은 JPA API를 사용한다. 조건식에 검색값을 직접 연결하지 않는다.
    // 회귀 검증: ContractRepositoryIntegrationTest.bindsSqlLikeContractNumberAsData
    @SuppressWarnings("java:S2077")
    @Transactional(readOnly = true)
    public Page<ContractView> search(
            ContractSearchCondition condition,
            Pageable pageable
    ) {
        Map<String, Object> parameters = new LinkedHashMap<>();

        // 목록과 건수 조회에서 같은 JOIN·검색 조건 사용
        String fromAndWhere = FROM_SQL + buildWhere(condition, parameters);

        NativeQuery<ContractView> contentQuery = createContentQuery(
                SELECT_SQL
                        + fromAndWhere
                        + " ORDER BY ic.contract_id DESC"
        );

        Query countQuery = entityManager.createNativeQuery(
                "SELECT COUNT(*) " + fromAndWhere
        );

        // 검색값은 SQL 문자열에 직접 넣지 않고 파라미터로 바인딩
        parameters.forEach((name, value) -> {
            contentQuery.setParameter(name, value);
            countQuery.setParameter(name, value);
        });

        // 기존 LIMIT·OFFSET에 해당
        contentQuery.setFirstResult(
                Math.toIntExact(pageable.getOffset())
        );
        contentQuery.setMaxResults(pageable.getPageSize());

        List<ContractView> content = contentQuery.getResultList();

        long totalCount =
                ((Number) countQuery.getSingleResult()).longValue();

        return new PageImpl<>(content, pageable, totalCount);
    }

    private String buildWhere(
            ContractSearchCondition condition,
            Map<String, Object> parameters
    ) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");

        if (condition == null) {
            return where.toString();
        }

        // 기존 XML과 동일하게 null·빈 문자열만 제외
        if (condition.getContractNo() != null
                && !condition.getContractNo().isEmpty()) {
            appendCondition(
                    where,
                    parameters,
                    "ic.contract_no LIKE CONCAT('%', :contractNo, '%')",
                    "contractNo",
                    condition.getContractNo()
            );
        }

        appendCondition(
                where, parameters,
                "ic.insurer_id = :insurerId",
                "insurerId", condition.getInsurerId()
        );

        appendCondition(
                where, parameters,
                "ic.product_offering_id = :productOfferingId",
                "productOfferingId", condition.getProductOfferingId()
        );

        appendCondition(
                where, parameters,
                "ic.agent_id = :agentId",
                "agentId", condition.getAgentId()
        );

        appendCondition(
                where, parameters,
                "ic.organization_id = :orgId",
                "orgId", condition.getOrgId()
        );

        appendCondition(
                where, parameters,
                "cc.result_status = :capResultStatus",
                "capResultStatus", condition.getCapResultStatus()
        );

        appendCondition(
                where, parameters,
                "ic.current_status = :currentStatus",
                "currentStatus", condition.getCurrentStatus()
        );

        appendCondition(
                where, parameters,
                "ic.contract_date >= :contractDateFrom",
                "contractDateFrom", condition.getContractDateFrom()
        );

        appendCondition(
                where, parameters,
                "ic.contract_date <= :contractDateTo",
                "contractDateTo", condition.getContractDateTo()
        );

        return where.toString();
    }

    private void appendCondition(
            StringBuilder where,
            Map<String, Object> parameters,
            String expression,
            String parameterName,
            Object value
    ) {
        if (value == null) {
            return;
        }

        where.append(" AND ").append(expression);

        // 네이티브 SQL에서는 DB에 저장된 enum 이름으로 비교
        Object parameterValue =
                value instanceof Enum<?> enumValue
                        ? enumValue.name()
                        : value;

        parameters.put(parameterName, parameterValue);
    }

    private NativeQuery<ContractView> createContentQuery(String sql) {
        NativeQuery<?> query = entityManager
                .createNativeQuery(sql)
                .unwrap(NativeQuery.class);

        // 조회 결과의 Java 타입을 명시
        query.addScalar("contract_id", Long.class);
        query.addScalar("contract_no", String.class);
        query.addScalar("insurer_name", String.class);
        query.addScalar("product_name", String.class);
        query.addScalar("contract_date", LocalDate.class);
        query.addScalar("monthly_equivalent_first_premium", BigDecimal.class);
        query.addScalar("agent_id_name", String.class);
        query.addScalar("contract_status", String.class);
        query.addScalar("cap_result_status", String.class);
        query.addScalar("data_origin", String.class);

        return query.setTupleTransformer(
                (row, aliases) -> toContractView(row)
        );
    }

    private ContractView toContractView(Object[] row) {
        String capResultStatus = (String) row[8];

        return ContractView.builder()
                .contractId((Long) row[0])
                .contractNo((String) row[1])
                .insurerName((String) row[2])
                .productName((String) row[3])
                .contractDate((LocalDate) row[4])
                .monthlyEquivalentFirstPremium((BigDecimal) row[5])
                .agentIdName((String) row[6])
                .contractStatus(
                        ContractStatus.valueOf((String) row[7])
                )
                .capResultStatus(
                        capResultStatus == null
                                ? null
                                : CapResultStatus.valueOf(capResultStatus)
                )
                .dataOrigin(
                        DataOrigin.valueOf((String) row[9])
                )
                .build();
    }

    @Transactional(readOnly = true)
    public List<ContractView> searchAll(
            ContractSearchCondition condition
    ) {
        Map<String, Object> parameters = new LinkedHashMap<>();

        String sql = SELECT_SQL
                + FROM_SQL
                + buildWhere(condition, parameters)
                + " ORDER BY ic.contract_id DESC";

        NativeQuery<ContractView> query = createContentQuery(sql);

        parameters.forEach((name, value) ->
                query.setParameter(name, value)
        );

        return query.getResultList();
    }
}
