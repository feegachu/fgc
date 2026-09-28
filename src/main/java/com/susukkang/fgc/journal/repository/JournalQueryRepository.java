package com.susukkang.fgc.journal.repository;

import com.susukkang.fgc.journal.dto.JournalBalanceSummary;
import com.susukkang.fgc.journal.dto.JournalDetailHeaderRow;
import com.susukkang.fgc.journal.dto.JournalDetailLineRow;
import com.susukkang.fgc.journal.dto.JournalListRow;
import com.susukkang.fgc.journal.dto.LedgerImbalanceRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 설명 : 원장 목록·상세·차대변 합계를 조회한다.
 * 공용 엔티티 조회는 JPQL, 정정그룹 조인과 불균형 뷰 조회는 네이티브 SQL을 사용한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JournalQueryRepository {

    private static final String LIST_SELECT = """
            SELECT h.journalHeaderId AS journalHeaderId, h.journalNo AS journalNo,
                   h.journalDate AS journalDate, cast(h.journalType as string) AS journalType,
                   h.sourceEntityType AS sourceEntityType, h.sourceEntityId AS sourceEntityId,
                   h.contractId AS contractId, c.contractNo AS contractNo,
                   cast(h.status as string) AS status,
                   (SELECT COALESCE(SUM(l.debitAmount), 0) FROM JournalLine l
                     WHERE l.journalHeaderId = h.journalHeaderId) AS debitTotal,
                   (SELECT COALESCE(SUM(l.creditAmount), 0) FROM JournalLine l
                     WHERE l.journalHeaderId = h.journalHeaderId) AS creditTotal,
                   h.reversalOfId AS reversalOfId, rev.journalNo AS reversalOfJournalNo,
                   creator.loginId AS createdBy, poster.loginId AS postedBy,
                   h.postedAt AS postedAt, h.createdAt AS createdAt
              FROM JournalHeader h
              LEFT JOIN InsuranceContract c ON c.contractId = h.contractId
              LEFT JOIN JournalHeader rev ON rev.journalHeaderId = h.reversalOfId
              LEFT JOIN AppUser creator ON creator.userId = h.createdBy
              LEFT JOIN AppUser poster ON poster.userId = h.postedBy
            """;

    private static final String IMBALANCE_FROM = """
              FROM fgc.vw_journal_imbalance vw
              JOIN fgc.journal_header h ON h.journal_header_id = vw.journal_header_id
             WHERE h.validation_run_id = :validationRunId
            """;

    private final EntityManager entityManager;

    /** 검증 실행 엔티티가 제공되기 전까지 불균형 조회 대상 실행의 존재 여부만 확인한다. */
    public boolean existsValidationRun(Long validationRunId) {
        return (Boolean) entityManager.createNativeQuery("""
                SELECT EXISTS (
                    SELECT 1 FROM fgc.validation_run WHERE validation_run_id = :validationRunId
                )
                """)
                .setParameter("validationRunId", validationRunId)
                .getSingleResult();
    }

    /** 계정과목 조건은 EXISTS로 검사하고 합계는 해당 헤더의 모든 라인으로 계산한다. */
    // S2077 검토: searchWhere는 코드에 고정된 조건식만 조합하며 검색값은 모두 setParameter로 바인딩한다.
    // ORDER BY는 고정이고 페이징은 JPA API를 사용한다. 사용자 입력을 JPQL 문자열에 연결하지 않는다.
    // 회귀 검증: JournalSearchQueryRepositoryIntegrationTest의 bindsSqlLikeSearchValuesAsData/bindsSqlLikeAccountCodeAsData.
    @SuppressWarnings("java:S2077")
    public List<JournalListRow> search(LocalDate from, LocalDate to, String journalType,
                                       String accountCode, Long contractId, String status,
                                       int offset, int limit) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String where = searchWhere(from, to, journalType, accountCode, contractId, status, parameters);
        TypedQuery<Tuple> query = entityManager.createQuery(
                LIST_SELECT + where + " ORDER BY h.journalDate DESC, h.journalHeaderId DESC", Tuple.class);
        parameters.forEach(query::setParameter);
        return query.setFirstResult(offset).setMaxResults(limit).getResultList().stream()
                .map(this::toListRow)
                .toList();
    }

    /** 목록과 같은 조건을 사용해 페이징 전 전체 헤더 수를 조회한다. */
    // S2077 검토: search와 동일한 고정 조건식만 사용하며 모든 검색값은 setParameter로 바인딩한다.
    // 회귀 검증: JournalSearchQueryRepositoryIntegrationTest에서 SQL 형태 입력의 목록과 count를 함께 확인한다.
    @SuppressWarnings("java:S2077")
    public long count(LocalDate from, LocalDate to, String journalType,
                      String accountCode, Long contractId, String status) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String where = searchWhere(from, to, journalType, accountCode, contractId, status, parameters);
        TypedQuery<Long> query = entityManager.createQuery(
                "SELECT COUNT(h) FROM JournalHeader h " + where, Long.class);
        parameters.forEach(query::setParameter);
        return query.getSingleResult();
    }

    /**
     * 미조회 시 기존 상세 Mapper와 동일하게 null을 반환한다.
     * 원분개·역분개·재기표를 함께 연결하는 기존 조인 조건은 네이티브 SQL로 유지한다.
     */
    public JournalDetailHeaderRow findHeaderById(Long journalHeaderId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT h.journal_header_id, h.journal_no, h.journal_date, h.journal_type,
                       h.source_entity_type, h.source_entity_id, h.revision_no,
                       h.contract_id, c.contract_no,
                       h.validation_run_id, h.policy_version_id,
                       COALESCE(h.correction_group_key, correction_group.correction_group_key) AS correction_group_key,
                       h.status, h.description,
                       creator.login_id AS created_by, h.created_at,
                       poster.login_id AS posted_by, h.posted_at,
                       h.reversal_of_id, rev.journal_no AS reversal_of_journal_no,
                       successor.journal_header_id AS reversed_by_journal_header_id,
                       successor.journal_no AS reversed_by_journal_no,
                       repost.journal_header_id AS reposted_journal_header_id,
                       repost.journal_no AS reposted_journal_no
                  FROM fgc.journal_header h
                  LEFT JOIN fgc.insurance_contract c ON c.contract_id = h.contract_id
                  LEFT JOIN fgc.app_user creator ON creator.user_id = h.created_by
                  LEFT JOIN fgc.app_user poster ON poster.user_id = h.posted_by
                  LEFT JOIN fgc.journal_header rev ON rev.journal_header_id = h.reversal_of_id
                  LEFT JOIN fgc.journal_header successor ON successor.reversal_of_id = h.journal_header_id
                  LEFT JOIN fgc.journal_correction_group correction_group
                         ON correction_group.original_journal_header_id = h.journal_header_id
                         OR correction_group.correction_group_key = h.correction_group_key
                  LEFT JOIN fgc.journal_header repost
                         ON repost.correction_group_key = correction_group.correction_group_key
                        AND repost.journal_type <> 'REVERSAL'
                 WHERE h.journal_header_id = :journalHeaderId
                """).unwrap(NativeQuery.class);
        query.setParameter("journalHeaderId", journalHeaderId);
        query.addScalar("journal_header_id", Long.class);
        query.addScalar("journal_no", String.class);
        query.addScalar("journal_date", LocalDate.class);
        query.addScalar("journal_type", String.class);
        query.addScalar("source_entity_type", String.class);
        query.addScalar("source_entity_id", String.class);
        query.addScalar("revision_no", Integer.class);
        query.addScalar("contract_id", Long.class);
        query.addScalar("contract_no", String.class);
        query.addScalar("validation_run_id", Long.class);
        query.addScalar("policy_version_id", Long.class);
        query.addScalar("correction_group_key", String.class);
        query.addScalar("status", String.class);
        query.addScalar("description", String.class);
        query.addScalar("created_by", String.class);
        query.addScalar("created_at", OffsetDateTime.class);
        query.addScalar("posted_by", String.class);
        query.addScalar("posted_at", OffsetDateTime.class);
        query.addScalar("reversal_of_id", Long.class);
        query.addScalar("reversal_of_journal_no", String.class);
        query.addScalar("reversed_by_journal_header_id", Long.class);
        query.addScalar("reversed_by_journal_no", String.class);
        query.addScalar("reposted_journal_header_id", Long.class);
        query.addScalar("reposted_journal_no", String.class);
        return query.setTupleTransformer((row, aliases) -> toDetailHeaderRow(row)).uniqueResult();
    }

    public List<JournalDetailLineRow> findLinesByHeaderId(Long journalHeaderId) {
        return entityManager.createQuery("""
                SELECT l.lineNo AS lineNo, a.accountCode AS accountCode, a.accountName AS accountName,
                       cast(a.normalBalance as string) AS normalBalance,
                       l.debitAmount AS debitAmount, l.creditAmount AS creditAmount,
                       l.contractId AS contractId, l.agentId AS agentId, ag.agentName AS agentName,
                       cast(l.paymentStage as string) AS paymentStage,
                       l.commissionItemId AS commissionItemId, ci.itemName AS commissionItemName,
                       l.memo AS memo
                  FROM JournalLine l
                  JOIN JournalAccount a ON a.journalAccountId = l.journalAccountId
                  LEFT JOIN Agent ag ON ag.agentId = l.agentId
                  LEFT JOIN CommissionItem ci ON ci.commissionItemId = l.commissionItemId
                 WHERE l.journalHeaderId = :journalHeaderId
                 ORDER BY l.lineNo
                """, Tuple.class)
                .setParameter("journalHeaderId", journalHeaderId)
                .getResultList().stream()
                .map(this::toDetailLineRow)
                .toList();
    }

    /** 라인이 없는 헤더는 GROUP BY 결과가 없으므로 null을 반환한다. */
    public JournalBalanceSummary findBalanceSummary(Long journalHeaderId) {
        return entityManager.createQuery("""
                SELECT l.journalHeaderId AS journalHeaderId,
                       COALESCE(SUM(l.debitAmount), 0) AS debitTotal,
                       COALESCE(SUM(l.creditAmount), 0) AS creditTotal
                  FROM JournalLine l
                 WHERE l.journalHeaderId = :journalHeaderId
                 GROUP BY l.journalHeaderId
                """, Tuple.class)
                .setParameter("journalHeaderId", journalHeaderId)
                .getResultList().stream()
                .map(row -> {
                    JournalBalanceSummary result = new JournalBalanceSummary();
                    result.setJournalHeaderId(row.get("journalHeaderId", Long.class));
                    result.setDebitTotal(row.get("debitTotal", BigDecimal.class));
                    result.setCreditTotal(row.get("creditTotal", BigDecimal.class));
                    return result;
                })
                .findFirst().orElse(null);
    }

    /** 기존 불균형 뷰의 집계와 DRAFT 포함 조건을 그대로 사용한다. */
    public List<LedgerImbalanceRow> findImbalances(Long validationRunId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT vw.journal_header_id, vw.journal_no, h.journal_type, vw.status,
                       vw.debit_total, vw.credit_total, vw.difference_amount
                """ + IMBALANCE_FROM + " ORDER BY vw.journal_header_id").unwrap(NativeQuery.class);
        query.setParameter("validationRunId", validationRunId);
        query.addScalar("journal_header_id", Long.class);
        query.addScalar("journal_no", String.class);
        query.addScalar("journal_type", String.class);
        query.addScalar("status", String.class);
        query.addScalar("debit_total", BigDecimal.class);
        query.addScalar("credit_total", BigDecimal.class);
        query.addScalar("difference_amount", BigDecimal.class);
        return query.setTupleTransformer((row, aliases) -> {
            LedgerImbalanceRow result = new LedgerImbalanceRow();
            result.setJournalHeaderId((Long) row[0]);
            result.setJournalNo((String) row[1]);
            result.setJournalType((String) row[2]);
            result.setStatus((String) row[3]);
            result.setDebitTotal((BigDecimal) row[4]);
            result.setCreditTotal((BigDecimal) row[5]);
            result.setDifferenceAmount((BigDecimal) row[6]);
            return result;
        }).getResultList();
    }

    public long countImbalances(Long validationRunId) {
        return ((Number) entityManager.createNativeQuery("SELECT COUNT(*) " + IMBALANCE_FROM)
                .setParameter("validationRunId", validationRunId)
                .getSingleResult()).longValue();
    }

    /** DRAFT 및 라인이 없는 헤더도 해당 검증 실행의 전체 건수에 포함한다. */
    public long countJournals(Long validationRunId) {
        return entityManager.createQuery("""
                SELECT COUNT(h) FROM JournalHeader h
                 WHERE h.validationRunId = :validationRunId
                """, Long.class)
                .setParameter("validationRunId", validationRunId)
                .getSingleResult();
    }

    private String searchWhere(LocalDate from, LocalDate to, String journalType,
                               String accountCode, Long contractId, String status,
                               Map<String, Object> parameters) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        appendCondition(where, parameters, "h.journalDate >= :fromDate", "fromDate", from);
        appendCondition(where, parameters, "h.journalDate <= :toDate", "toDate", to);
        // 문자열 조건을 유지해 미정의 코드·빈 문자열도 기존 SQL처럼 빈 결과가 되도록 한다.
        appendCondition(where, parameters, "cast(h.journalType as string) = :journalType", "journalType", journalType);
        appendCondition(where, parameters, "h.contractId = :contractId", "contractId", contractId);
        appendCondition(where, parameters, "cast(h.status as string) = :status", "status", status);
        appendCondition(where, parameters, """
                EXISTS (
                    SELECT 1 FROM JournalLine l2
                    JOIN JournalAccount a2 ON a2.journalAccountId = l2.journalAccountId
                    WHERE l2.journalHeaderId = h.journalHeaderId AND a2.accountCode = :accountCode
                )
                """, "accountCode", accountCode);
        return where.toString();
    }

    private void appendCondition(StringBuilder where, Map<String, Object> parameters,
                                 String expression, String parameterName, Object value) {
        if (value != null) {
            where.append(" AND ").append(expression);
            parameters.put(parameterName, value);
        }
    }

    private JournalListRow toListRow(Tuple row) {
        JournalListRow result = new JournalListRow();
        result.setJournalHeaderId(row.get("journalHeaderId", Long.class));
        result.setJournalNo(row.get("journalNo", String.class));
        result.setJournalDate(row.get("journalDate", LocalDate.class));
        result.setJournalType(row.get("journalType", String.class));
        result.setSourceEntityType(row.get("sourceEntityType", String.class));
        result.setSourceEntityId(row.get("sourceEntityId", String.class));
        result.setContractId(row.get("contractId", Long.class));
        result.setContractNo(row.get("contractNo", String.class));
        result.setStatus(row.get("status", String.class));
        result.setDebitTotal(row.get("debitTotal", BigDecimal.class));
        result.setCreditTotal(row.get("creditTotal", BigDecimal.class));
        result.setReversalOfId(row.get("reversalOfId", Long.class));
        result.setReversalOfJournalNo(row.get("reversalOfJournalNo", String.class));
        result.setCreatedBy(row.get("createdBy", String.class));
        result.setPostedBy(row.get("postedBy", String.class));
        result.setPostedAt(row.get("postedAt", OffsetDateTime.class));
        result.setCreatedAt(row.get("createdAt", OffsetDateTime.class));
        return result;
    }

    private JournalDetailHeaderRow toDetailHeaderRow(Object[] row) {
        JournalDetailHeaderRow result = new JournalDetailHeaderRow();
        result.setJournalHeaderId((Long) row[0]);
        result.setJournalNo((String) row[1]);
        result.setJournalDate((LocalDate) row[2]);
        result.setJournalType((String) row[3]);
        result.setSourceEntityType((String) row[4]);
        result.setSourceEntityId((String) row[5]);
        result.setRevisionNo((Integer) row[6]);
        result.setContractId((Long) row[7]);
        result.setContractNo((String) row[8]);
        result.setValidationRunId((Long) row[9]);
        result.setPolicyVersionId((Long) row[10]);
        result.setCorrectionGroupKey((String) row[11]);
        result.setStatus((String) row[12]);
        result.setDescription((String) row[13]);
        result.setCreatedBy((String) row[14]);
        result.setCreatedAt((OffsetDateTime) row[15]);
        result.setPostedBy((String) row[16]);
        result.setPostedAt((OffsetDateTime) row[17]);
        result.setReversalOfId((Long) row[18]);
        result.setReversalOfJournalNo((String) row[19]);
        result.setReversedByJournalHeaderId((Long) row[20]);
        result.setReversedByJournalNo((String) row[21]);
        result.setRepostedJournalHeaderId((Long) row[22]);
        result.setRepostedJournalNo((String) row[23]);
        return result;
    }

    private JournalDetailLineRow toDetailLineRow(Tuple row) {
        JournalDetailLineRow result = new JournalDetailLineRow();
        result.setLineNo(row.get("lineNo", Integer.class));
        result.setAccountCode(row.get("accountCode", String.class));
        result.setAccountName(row.get("accountName", String.class));
        result.setNormalBalance(row.get("normalBalance", String.class));
        result.setDebitAmount(row.get("debitAmount", BigDecimal.class));
        result.setCreditAmount(row.get("creditAmount", BigDecimal.class));
        result.setContractId(row.get("contractId", Long.class));
        result.setAgentId(row.get("agentId", Long.class));
        result.setAgentName(row.get("agentName", String.class));
        result.setPaymentStage(row.get("paymentStage", String.class));
        result.setCommissionItemId(row.get("commissionItemId", Long.class));
        result.setCommissionItemName(row.get("commissionItemName", String.class));
        result.setMemo(row.get("memo", String.class));
        return result;
    }
}
