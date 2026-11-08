package com.susukkang.fgc.report.repository;

import com.susukkang.fgc.report.dto.StatementAgentOverview;
import com.susukkang.fgc.common.security.AgentDataScopeService;
import com.susukkang.fgc.common.web.ListSort;
import com.susukkang.fgc.common.web.SqlSort;
import com.susukkang.fgc.report.dto.StatementSummary;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * FUN-060 설계사 수수료 명세 조회. 지급·보류·조정·환수 네 원천을 한 CTE(entries)로 모아
 * 목록·합계·상세가 같은 본인 귀속 모집단을 쓰게 한다.
 *
 * <p>검색값은 모두 바인딩하고, 정렬은 허용 목록(SORTABLE)의 코드 상수 표현식만 쓴다.
 * 같은 지급건은 한 유형에만 들어가므로 원천이 이중으로 합산되지 않는다.
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentStatementRepository {
    public static final String PAYMENT = "PAYMENT";
    public static final String HELD = "HELD";
    public static final String ADJUSTMENT = "ADJUSTMENT";
    public static final String CLAWBACK = "CLAWBACK";
    public static final Set<String> ENTRY_TYPES = Set.of(PAYMENT, HELD, ADJUSTMENT, CLAWBACK);

    private static final String ENTRY_TYPE = "e.entry_type";
    private static final String AGENT_ID = "agentId";
    private static final String ENTRY_TYPE_PARAM = "entryType";

    private static final Map<String, String> SORTABLE = Map.of(
            "basisDate", "e.basis_date",
            "entryType", ENTRY_TYPE,
            "contractNo", "ic.contract_no",
            "commissionItemCode", "ci.item_code",
            "installmentNo", "e.installment_no",
            "statusCode", "e.status_code",
            "amount", "e.amount");
    public static final Set<String> SORT_KEYS = Set.copyOf(SORTABLE.keySet());
    public static final ListSort DEFAULT_SORT = new ListSort("basisDate", false);

    /**
     * 환수는 탐지만 된 것(DETECTED)과 취소(CANCELLED)를 뺀 케이스의 본인 귀속 라인이다.
     * 계산 버전 컬럼이 아직 없어(055) 케이스의 현재 상태를 계산판으로 본다.
     */
    private static final String ENTRIES_CTE = """
            WITH entries AS (
              SELECT 'PAYMENT' AS entry_type, ta.transaction_attribution_id AS source_id, 0::bigint AS sub_id,
                     ta.contract_id, ct.commission_item_id, ct.installment_no,
                     ta.attribution_date AS basis_date, ta.attribution_month AS basis_month,
                     ct.status AS status_code, ta.attributed_amount AS amount, ct.policy_version_id,
                     'TRANSACTION_ATTRIBUTION' AS source_entity_type,
                     jsonb_build_object('commissionTransactionId', ct.commission_transaction_id,
                                        'sourceType', ct.source_type, 'attributionMethod', ta.attribution_method,
                                        'settlementMonth', ct.settlement_month, 'paidOn', ct.paid_on,
                                        'scheduleLineId', sl.schedule_line_id,
                                        'scheduleHeaderId', sl.schedule_header_id,
                                        'paymentConditionCode', sl.payment_condition_code,
                                        'basisCode', sl.basis_code, 'basisAmount', sl.basis_amount,
                                        'calculationType', sl.calculation_type,
                                        'ratePct', sl.rate_pct, 'fixedAmount', sl.fixed_amount) AS detail
                FROM fgc.commission_transaction ct
                JOIN fgc.transaction_attribution ta ON ta.commission_transaction_id = ct.commission_transaction_id
                -- 스케줄 근거는 같은 계약의 회차이고 수혜자가 본인일 때만 붙인다(잘못 연결된 자료로 남의 계산 근거가 나가지 않게).
                LEFT JOIN fgc.schedule_line sl ON sl.schedule_line_id = ta.schedule_line_id
                     AND sl.beneficiary_agent_id = ta.agent_id
                     AND EXISTS (SELECT 1 FROM fgc.schedule_header sh WHERE sh.schedule_header_id = sl.schedule_header_id
                                  AND sh.contract_id = ta.contract_id)
               WHERE ct.recipient_agent_id = :agentId AND __OWN_TA__ AND ct.payment_stage = 'GA_TO_FC'
                 AND ct.status = 'CONFIRMED' AND ct.cashflow_type = 'PAYMENT'
                 AND ct.source_type NOT IN ('ADJUSTMENT', 'CLAWBACK', 'RECOVERY')
                 AND NOT EXISTS (SELECT 1 FROM fgc.payment_journal_correction pc
                                  WHERE pc.replacement_payment_id = ct.commission_transaction_id)
              UNION ALL
              SELECT 'ADJUSTMENT', ct.commission_transaction_id, COALESCE(ta.transaction_attribution_id, 0),
                     ta.contract_id, ct.commission_item_id, ct.installment_no,
                     COALESCE(ct.paid_on, ct.due_date, ct.settlement_month), ct.settlement_month,
                     ct.status,
                     CASE WHEN ct.cashflow_type = 'DEDUCTION' THEN -COALESCE(ta.attributed_amount, ct.amount)
                          ELSE COALESCE(ta.attributed_amount, ct.amount) END,
                     ct.policy_version_id, 'COMMISSION_TRANSACTION',
                     jsonb_build_object('commissionTransactionId', ct.commission_transaction_id,
                                        'cashflowType', ct.cashflow_type,
                                        'note', CASE WHEN __MIXED__ THEN NULL ELSE ct.note END,
                                        'replacesPaymentId', (SELECT p.original_payment_id
                                            FROM fgc.payment_journal_correction p
                                           WHERE p.replacement_payment_id = ct.commission_transaction_id),
                                        'replacedByPaymentId', (SELECT p.replacement_payment_id
                                            FROM fgc.payment_journal_correction p
                                           WHERE p.original_payment_id = ct.commission_transaction_id))
                FROM fgc.commission_transaction ct
                LEFT JOIN fgc.transaction_attribution ta ON ta.commission_transaction_id = ct.commission_transaction_id
                                                        AND __OWN_TA__
               WHERE ct.recipient_agent_id = :agentId AND ct.payment_stage = 'GA_TO_FC'
                 AND ct.status = 'CONFIRMED'
                 AND (ct.source_type = 'ADJUSTMENT'
                      OR EXISTS (SELECT 1 FROM fgc.payment_journal_correction pc
                                  WHERE pc.replacement_payment_id = ct.commission_transaction_id))
                 AND (ta.transaction_attribution_id IS NOT NULL
                      OR NOT EXISTS (SELECT 1 FROM fgc.transaction_attribution x
                                      WHERE x.commission_transaction_id = ct.commission_transaction_id))
              UNION ALL
              SELECT 'HELD', sl.schedule_line_id, 0::bigint,
                     sh.contract_id, sl.commission_item_id, sl.installment_no,
                     sl.due_date, sl.due_month,
                     sl.line_status, sl.expected_amount, sh.policy_version_id, 'SCHEDULE_LINE',
                     jsonb_build_object('scheduleHeaderId', sh.schedule_header_id,
                                        'scheduleVersionNo', sh.schedule_version_no,
                                        'paymentConditionCode', sl.payment_condition_code,
                                        'basisCode', sl.basis_code, 'basisAmount', sl.basis_amount,
                                        'calculationType', sl.calculation_type,
                                        'ratePct', sl.rate_pct, 'fixedAmount', sl.fixed_amount)
                FROM fgc.schedule_line sl
                JOIN fgc.schedule_header sh ON sh.schedule_header_id = sl.schedule_header_id
               WHERE sl.beneficiary_agent_id = :agentId AND sh.payment_stage = 'GA_TO_FC'
                 AND sh.schedule_purpose = 'OPERATIONAL' AND sh.active_yn AND sl.line_status = 'HOLD'
              UNION ALL
              SELECT 'CLAWBACK', cl.clawback_line_id, 0::bigint,
                     cc.contract_id, ct.commission_item_id, ct.installment_no,
                     (cc.created_at AT TIME ZONE 'Asia/Seoul')::date,
                     date_trunc('month', cc.created_at AT TIME ZONE 'Asia/Seoul')::date,
                     cc.status, cl.clawback_amount, cc.policy_version_id, 'CLAWBACK_LINE',
                     jsonb_build_object('clawbackCaseId', cc.clawback_case_id, 'caseNo', cc.case_no,
                                        'clawbackRuleId', cl.clawback_rule_id,
                                        'transactionAttributionId', cl.transaction_attribution_id,
                                        'originallyPaidAmount', cl.originally_paid_amount,
                                        'appliedRatePct', cl.applied_rate_pct)
                FROM fgc.clawback_line cl
                JOIN fgc.clawback_case cc ON cc.clawback_case_id = cl.clawback_case_id
                JOIN fgc.transaction_attribution ta ON ta.transaction_attribution_id = cl.transaction_attribution_id
                JOIN fgc.commission_transaction ct ON ct.commission_transaction_id = ta.commission_transaction_id
               WHERE ct.recipient_agent_id = :agentId AND __OWN_TA__
                 AND cc.status IN ('CALCULATED', 'NOTIFIED', 'OBJECTED', 'CONFIRMED', 'RECOVERING', 'CLOSED')
            )
            """.replace("__OWN_TA__", AgentDataScopeService.ownAttribution("ta"))
            .replace("__MIXED__", AgentDataScopeService.MIXED_PAYMENT);

    private static final String JOINS = """
              FROM entries e
              LEFT JOIN fgc.insurance_contract ic ON ic.contract_id = e.contract_id
              JOIN fgc.commission_item ci ON ci.commission_item_id = e.commission_item_id
              LEFT JOIN fgc.policy_version pv ON pv.policy_version_id = e.policy_version_id
            """;

    private static final String SELECT_ROWS = """
            SELECT e.entry_type, e.source_id, e.sub_id, e.contract_id, ic.contract_no, ci.item_code, ci.item_name,
                   e.installment_no, e.basis_date, e.status_code, e.amount, e.policy_version_id,
                   e.source_entity_type, e.detail::text, e.basis_month, pv.policy_name, pv.version_no
            """;

    private static final String OVERVIEW_AGGREGATE = ENTRIES_CTE.replace(":agentId", "a.agent_id") + """
                        SELECT count(DISTINCT e.contract_id) AS contracts, count(*) AS entries,
                               COALESCE(sum(e.amount) FILTER (WHERE e.entry_type = 'PAYMENT'), 0) AS payment,
                               COALESCE(sum(e.amount) FILTER (WHERE e.entry_type = 'HELD'), 0) AS held,
                               COALESCE(sum(e.amount) FILTER (WHERE e.entry_type = 'ADJUSTMENT'), 0) AS adjustment,
                               COALESCE(sum(e.amount) FILTER (WHERE e.entry_type = 'CLAWBACK'), 0) AS clawback,
                               count(*) FILTER (WHERE e.amount IS NULL) AS missing
                        """ + JOINS + """
            WHERE e.basis_month = :month
              AND (CAST(:contractNo AS text) IS NULL OR strpos(lower(ic.contract_no), lower(CAST(:contractNo AS text))) > 0)
              AND (CAST(:commissionItemId AS bigint) IS NULL OR e.commission_item_id = :commissionItemId)
              AND (CAST(:entryType AS text) IS NULL OR e.entry_type = :entryType)
            """;

    private static final String OVERVIEW_SQL = """
                SELECT a.agent_id, a.agent_code, a.agent_name,
                       COALESCE(b.organization_id, o.organization_id),
                       COALESCE(b.organization_name, o.organization_name), o.organization_name,
                       s.contracts, s.entries, s.payment, s.held, s.adjustment, s.clawback, s.missing,
                       o.organization_id, o.parent_id, a.rank_code
                  FROM fgc.agent a
                  JOIN fgc.organization o ON o.organization_id = a.organization_id
                  LEFT JOIN LATERAL (
                    WITH RECURSIVE ancestors AS (
                      SELECT organization_id, parent_id, organization_name, organization_type,
                             ARRAY[organization_id] AS path, 0 AS depth
                        FROM fgc.organization WHERE organization_id = a.organization_id
                      UNION ALL
                      SELECT p.organization_id, p.parent_id, p.organization_name, p.organization_type,
                             x.path || p.organization_id, x.depth + 1
                        FROM fgc.organization p JOIN ancestors x ON p.organization_id = x.parent_id
                       WHERE NOT p.organization_id = ANY(x.path)
                    )
                    SELECT organization_id, organization_name FROM ancestors
                     WHERE organization_type = 'BRANCH' ORDER BY depth LIMIT 1
                  ) b ON TRUE
                  CROSS JOIN LATERAL (
                """ + OVERVIEW_AGGREGATE + """
                  ) s
                 WHERE s.entries > 0 OR (a.appointment_date <= :monthEnd
                     AND (a.termination_date IS NULL OR a.termination_date >= :month))
                 ORDER BY s.entries > 0 DESC, COALESCE(b.organization_name, o.organization_name),
                          a.agent_name, a.agent_id
                """;

    private final EntityManager entityManager;

    /** 한 요청으로 전체 설계사를 조회하며, 개별 명세와 동일한 CTE/귀속 범위를 재사용한다. */
    public List<StatementAgentOverview> overview(Filter filter) {
        Map<Long, StatementAgentOverview.OrganizationNode> organizations = new HashMap<>();
        entityManager.createNativeQuery("SELECT organization_id,parent_id,organization_name,organization_type FROM fgc.organization")
                .getResultList().forEach(value -> {
                    Object[] row = (Object[]) value;
                    long id = ((Number) row[0]).longValue();
                    organizations.put(id, new StatementAgentOverview.OrganizationNode(id,
                            row[1] == null ? null : ((Number) row[1]).longValue(), (String) row[2], (String) row[3]));
                });
        Map<String, Object> params = new HashMap<>();
        params.put("month", filter.month());
        params.put("contractNo", filter.contractNo());
        params.put("commissionItemId", filter.commissionItemId());
        params.put("entryType", filter.entryType());
        NativeQuery<?> query = (NativeQuery<?>) entityManager.createNativeQuery(OVERVIEW_SQL);
        params.forEach(query::setParameter);
        query.setParameter("monthEnd", filter.month().withDayOfMonth(filter.month().lengthOfMonth()));
        return query.getResultList().stream().map(result -> {
            Object[] c = (Object[]) result;
            List<StatementAgentOverview.OrganizationNode> path = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            Long organizationId = ((Number) c[13]).longValue();
            while (organizationId != null && seen.add(organizationId) && organizations.containsKey(organizationId)) {
                var organization = organizations.get(organizationId);
                path.add(0, organization);
                organizationId = organization.parentId();
            }
            return new StatementAgentOverview(
                    ((Number) c[0]).longValue(), (String) c[1], (String) c[2], ((Number) c[3]).longValue(),
                    (String) c[4], (String) c[5], ((Number) c[6]).longValue(), ((Number) c[7]).longValue(),
                    (BigDecimal) c[8], (BigDecimal) c[9], (BigDecimal) c[10], (BigDecimal) c[11],
                    ((Number) c[12]).longValue(), ((Number) c[13]).longValue(),
                    c[14] == null ? null : ((Number) c[14]).longValue(), (String) c[15], List.copyOf(path));
        }).toList();
    }

    /** 목록·합계가 같이 쓰는 필터. month 는 그 달 1일이다. */
    public record Filter(long agentId, LocalDate month, String contractNo, Long commissionItemId, String entryType) {
    }

    public record Row(String entryType, long sourceId, long subId, Long contractId, String contractNo,
                      String itemCode, String itemName, Integer installmentNo, LocalDate basisDate,
                      String statusCode, BigDecimal amount, Long policyVersionId, String sourceEntityType,
                      String detailJson, LocalDate basisMonth, String policyName, Integer policyVersionNo) {
    }

    public boolean agentExists(long agentId) {
        Number count = (Number) entityManager.createNativeQuery("SELECT count(*) FROM fgc.agent WHERE agent_id = :id")
                .setParameter("id", agentId).getSingleResult();
        return count.longValue() > 0;
    }

    public List<Row> search(Filter filter, ListSort sort, long offset, int limit) {
        StringBuilder sql = new StringBuilder(ENTRIES_CTE).append(SELECT_ROWS).append(JOINS);
        Map<String, Object> params = where(sql, filter);
        sql.append(SqlSort.orderBy(sort, SORTABLE, "e.entry_type ASC, e.source_id ASC, e.sub_id ASC"));
        sql.append(" LIMIT :limit OFFSET :offset");
        NativeQuery<?> query = (NativeQuery<?>) entityManager.createNativeQuery(sql.toString());
        params.forEach(query::setParameter);
        query.setParameter("limit", limit).setParameter("offset", offset);
        return rows(query.getResultList());
    }

    /** 같은 필터 전체의 유형별 건수·금액. 목록의 totalElements 도 여기서 구한다. */
    public StatementSummary summary(Filter filter) {
        StringBuilder sql = new StringBuilder(ENTRIES_CTE)
                .append("SELECT e.entry_type, count(*), COALESCE(sum(e.amount), 0)\n").append(JOINS);
        Map<String, Object> params = where(sql, filter);
        sql.append(" GROUP BY e.entry_type");
        NativeQuery<?> query = (NativeQuery<?>) entityManager.createNativeQuery(sql.toString());
        params.forEach(query::setParameter);
        Map<String, StatementSummary.Bucket> buckets = new HashMap<>();
        for (Object result : query.getResultList()) {
            Object[] cols = (Object[]) result;
            buckets.put((String) cols[0], new StatementSummary.Bucket(((Number) cols[1]).longValue(), (BigDecimal) cols[2]));
        }
        return new StatementSummary(
                buckets.getOrDefault(PAYMENT, StatementSummary.Bucket.empty()),
                buckets.getOrDefault(HELD, StatementSummary.Bucket.empty()),
                buckets.getOrDefault(ADJUSTMENT, StatementSummary.Bucket.empty()),
                buckets.getOrDefault(CLAWBACK, StatementSummary.Bucket.empty()));
    }

    /** 상세: 목록과 같은 본인 범위 안에서만 찾는다. 남의 원천이거나 없으면 비어 있다. */
    public Optional<Row> find(long agentId, String entryType, long sourceId, long subId) {
        StringBuilder sql = new StringBuilder(ENTRIES_CTE).append(SELECT_ROWS).append(JOINS)
                .append(" WHERE e.entry_type = :entryType AND e.source_id = :sourceId AND e.sub_id = :subId");
        NativeQuery<?> query = (NativeQuery<?>) entityManager.createNativeQuery(sql.toString());
        query.setParameter(AGENT_ID, agentId).setParameter(ENTRY_TYPE_PARAM, entryType)
                .setParameter("sourceId", sourceId).setParameter("subId", subId);
        return rows(query.getResultList()).stream().findFirst();
    }

    /** 필터 조건은 고정 문구만 붙이고 값은 바인딩한다. 계약번호는 대소문자 무시 부분일치(LIKE 와일드카드 해석 없음). */
    private static Map<String, Object> where(StringBuilder sql, Filter filter) {
        Map<String, Object> params = new HashMap<>();
        params.put(AGENT_ID, filter.agentId());
        params.put("month", filter.month());
        sql.append(" WHERE e.basis_month = :month");
        if (filter.contractNo() != null) {
            sql.append(" AND strpos(lower(ic.contract_no), lower(:contractNo)) > 0");
            params.put("contractNo", filter.contractNo());
        }
        if (filter.commissionItemId() != null) {
            sql.append(" AND e.commission_item_id = :commissionItemId");
            params.put("commissionItemId", filter.commissionItemId());
        }
        if (filter.entryType() != null) {
            sql.append(" AND ").append(ENTRY_TYPE).append(" = :entryType");
            params.put(ENTRY_TYPE_PARAM, filter.entryType());
        }
        sql.append(' ');
        return params;
    }

    private static List<Row> rows(List<?> results) {
        return results.stream().map(result -> {
            Object[] c = (Object[]) result;
            return new Row((String) c[0], ((Number) c[1]).longValue(), ((Number) c[2]).longValue(),
                    c[3] == null ? null : ((Number) c[3]).longValue(), (String) c[4], (String) c[5], (String) c[6],
                    c[7] == null ? null : ((Number) c[7]).intValue(), date(c[8]), (String) c[9], (BigDecimal) c[10],
                    c[11] == null ? null : ((Number) c[11]).longValue(), (String) c[12], (String) c[13], date(c[14]),
                    (String) c[15], c[16] == null ? null : ((Number) c[16]).intValue());
        }).toList();
    }

    private static LocalDate date(Object value) {
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return (LocalDate) value;
    }
}
