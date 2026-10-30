package com.susukkang.fgc.report.repository;

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
                                        'scheduleLineId', ta.schedule_line_id,
                                        'scheduleHeaderId', sl.schedule_header_id,
                                        'paymentConditionCode', sl.payment_condition_code,
                                        'basisCode', sl.basis_code, 'basisAmount', sl.basis_amount,
                                        'calculationType', sl.calculation_type,
                                        'ratePct', sl.rate_pct, 'fixedAmount', sl.fixed_amount) AS detail
                FROM fgc.commission_transaction ct
                JOIN fgc.transaction_attribution ta ON ta.commission_transaction_id = ct.commission_transaction_id
                LEFT JOIN fgc.schedule_line sl ON sl.schedule_line_id = ta.schedule_line_id
               WHERE ct.recipient_agent_id = :agentId AND ta.agent_id = :agentId AND ct.payment_stage = 'GA_TO_FC'
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
                                        'cashflowType', ct.cashflow_type, 'note', ct.note,
                                        'replacesPaymentId', (SELECT p.original_payment_id
                                            FROM fgc.payment_journal_correction p
                                           WHERE p.replacement_payment_id = ct.commission_transaction_id),
                                        'replacedByPaymentId', (SELECT p.replacement_payment_id
                                            FROM fgc.payment_journal_correction p
                                           WHERE p.original_payment_id = ct.commission_transaction_id))
                FROM fgc.commission_transaction ct
                LEFT JOIN fgc.transaction_attribution ta ON ta.commission_transaction_id = ct.commission_transaction_id
                                                        AND ta.agent_id = :agentId
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
               WHERE ct.recipient_agent_id = :agentId AND ta.agent_id = :agentId
                 AND cc.status IN ('CALCULATED', 'NOTIFIED', 'OBJECTED', 'CONFIRMED', 'RECOVERING', 'CLOSED')
            )
            """;

    private static final String JOINS = """
              FROM entries e
              LEFT JOIN fgc.insurance_contract ic ON ic.contract_id = e.contract_id
              JOIN fgc.commission_item ci ON ci.commission_item_id = e.commission_item_id
            """;

    private static final String SELECT_ROWS = """
            SELECT e.entry_type, e.source_id, e.sub_id, e.contract_id, ic.contract_no, ci.item_code, ci.item_name,
                   e.installment_no, e.basis_date, e.status_code, e.amount, e.policy_version_id,
                   e.source_entity_type, e.detail::text, e.basis_month
            """;

    private final EntityManager entityManager;

    /** 목록·합계가 같이 쓰는 필터. month 는 그 달 1일이다. */
    public record Filter(long agentId, LocalDate month, String contractNo, Long commissionItemId, String entryType) {
    }

    public record Row(String entryType, long sourceId, long subId, Long contractId, String contractNo,
                      String itemCode, String itemName, Integer installmentNo, LocalDate basisDate,
                      String statusCode, BigDecimal amount, Long policyVersionId, String sourceEntityType,
                      String detailJson, LocalDate basisMonth) {
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
        NativeQuery<?> query = (NativeQuery<?>) entityManager.createNativeQuery(ENTRIES_CTE + SELECT_ROWS + JOINS
                + " WHERE e.entry_type = :entryType AND e.source_id = :sourceId AND e.sub_id = :subId");
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
                    c[11] == null ? null : ((Number) c[11]).longValue(), (String) c[12], (String) c[13], date(c[14]));
        }).toList();
    }

    private static LocalDate date(Object value) {
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return (LocalDate) value;
    }
}
