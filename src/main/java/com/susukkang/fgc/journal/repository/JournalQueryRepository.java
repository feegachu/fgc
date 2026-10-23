package com.susukkang.fgc.journal.repository;

import com.susukkang.fgc.journal.dto.JournalBalanceSummary;
import com.susukkang.fgc.journal.dto.JournalDetailHeaderRow;
import com.susukkang.fgc.journal.dto.JournalDetailLineRow;
import com.susukkang.fgc.journal.dto.JournalListRow;
import com.susukkang.fgc.journal.dto.LedgerImbalanceRow;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.ExpressionUtils;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.Expressions;
import com.susukkang.fgc.common.web.ListSort;
import com.susukkang.fgc.common.web.QuerydslSort;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.susukkang.fgc.journal.entity.QJournalHeader;
import com.susukkang.fgc.auth.entity.QAppUser;
import com.susukkang.fgc.contract.entity.QInsuranceContract;
import static com.susukkang.fgc.journal.entity.QJournalHeader.journalHeader;
import static com.susukkang.fgc.journal.entity.QJournalLine.journalLine;
import static com.susukkang.fgc.journal.entity.QJournalAccount.journalAccount;
import static com.susukkang.fgc.contract.entity.QInsuranceContract.insuranceContract;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 설명 : 원장 목록·상세·차대변 합계를 조회한다.
 * 동적 검색은 QueryDSL, 고정 조회는 JPQL, 정정그룹 조인과 불균형 뷰는 네이티브 SQL을 사용한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JournalQueryRepository {
    private static final String DEBIT_TOTAL = "debitTotal";
    private static final String CREDIT_TOTAL = "creditTotal";


    private static final String STATUS_ALIAS = "status";
    private static final String VALIDATION_RUN_ID_PARAMETER = "validationRunId";

    private static final String IMBALANCE_FROM = """
              FROM fgc.vw_journal_imbalance vw
              JOIN fgc.journal_header h ON h.journal_header_id = vw.journal_header_id
             WHERE h.validation_run_id = :validationRunId
            """;

    private final EntityManager entityManager;
    private final JPAQueryFactory queryFactory;

    /** 화면 정렬 key(LEDG-W01 분개 목록) → 표현식. 차변·대변 합계는 목록과 같은 헤더 라인 합계 식이다. */
    private static final Map<String, ComparableExpressionBase<?>> SORTABLE = Map.of(
            "journalNo", journalNoNumericOrder(),
            "journalDate", journalHeader.journalDate,
            "contractNo", insuranceContract.contractNo,
            DEBIT_TOTAL, lineTotal(journalLine.debitAmount),
            CREDIT_TOTAL, lineTotal(journalLine.creditAmount));
    public static final Set<String> SORT_KEYS = Set.copyOf(SORTABLE.keySet());
    public static final ListSort DEFAULT_SORT = new ListSort("journalDate", false);

    /**
     * 분개번호 정렬식. 번호는 JV-YYYY-MM-NNNN(일련번호 4자리 이상)이라 문자열 그대로 정렬하면 10000번이 9999번보다 앞선다.
     * 월 접두(10자) 뒤의 일련번호를 8자리로 왼쪽 0 채워 일련번호를 숫자 순서로 정렬한다.
     */
    private static ComparableExpressionBase<String> journalNoNumericOrder() {
        return Expressions.stringTemplate(
                "concat(substring({0}, 1, 10), lpad(substring({0}, 12), 8, '0'))", journalHeader.journalNo);
    }

    private static ComparableExpressionBase<BigDecimal> lineTotal(
            com.querydsl.core.types.dsl.NumberPath<BigDecimal> amount) {
        return Expressions.numberTemplate(BigDecimal.class, "({0})",
                JPAExpressions.select(amount.sumAggregate().coalesce(BigDecimal.ZERO))
                        .from(journalLine).where(journalLine.journalHeaderId.eq(journalHeader.journalHeaderId)));
    }

    /** 요청 정렬을 검증한다. 허용 key 가 아니면 COMMON_002. 비어 있으면 분개일 내림차순. */
    public static ListSort sortOf(String raw) {
        return ListSort.parse(raw, SORT_KEYS, DEFAULT_SORT);
    }

    public boolean existsValidationRun(Long validationRunId) {
        return entityManager.createQuery("SELECT count(v) FROM ValidationRun v WHERE v.validationRunId = :id", Long.class)
                .setParameter("id", validationRunId).getSingleResult() > 0;
    }

    /** 계약번호 조건 없이 검색한다(기존 호출 호환). */
    public List<JournalListRow> search(LocalDate from, LocalDate to, String journalType,
                                       String accountCode, Long contractId, String status,
                                       int offset, int limit) {
        return search(from, to, journalType, accountCode, contractId, null, status, offset, limit);
    }

    /** 기본 정렬(분개일 내림차순)로 검색한다(기존 호출 호환). */
    public List<JournalListRow> search(LocalDate from, LocalDate to, String journalType,
                                       String accountCode, Long contractId, String contractNo, String status,
                                       int offset, int limit) {
        return search(from, to, journalType, accountCode, contractId, contractNo, status, offset, limit, DEFAULT_SORT);
    }

    /** 계정과목 필터는 EXISTS, 합계는 헤더의 모든 라인을 대상으로 유지한다. 정렬은 검색된 전체 데이터에 적용한다. */
    // S107: 기존 검색 인자에 정렬만 더했다(기존 호출 호환). 인자 묶음 객체로 바꾸면 기존 호출·테스트가 모두 바뀐다.
    @SuppressWarnings("java:S107")
    public List<JournalListRow> search(LocalDate from, LocalDate to, String journalType,
                                       String accountCode, Long contractId, String contractNo, String status,
                                       int offset, int limit, ListSort sort) {
        var h = journalHeader;
        var l = journalLine;
        var c = insuranceContract;
        var rev = new QJournalHeader("reversal");
        var creator = new QAppUser("creator");
        var poster = new QAppUser("poster");
        return queryFactory.select(Projections.bean(JournalListRow.class,
                        h.journalHeaderId, h.journalNo, h.journalDate, h.journalType.stringValue().as("journalType"),
                        h.sourceEntityType, h.sourceEntityId, h.contractId, c.contractNo,
                        h.status.stringValue().as(STATUS_ALIAS),
                        ExpressionUtils.as(JPAExpressions.select(l.debitAmount.sumAggregate().coalesce(BigDecimal.ZERO))
                                .from(l).where(l.journalHeaderId.eq(h.journalHeaderId)), DEBIT_TOTAL),
                        ExpressionUtils.as(JPAExpressions.select(l.creditAmount.sumAggregate().coalesce(BigDecimal.ZERO))
                                .from(l).where(l.journalHeaderId.eq(h.journalHeaderId)), CREDIT_TOTAL),
                        h.reversalOfId, rev.journalNo.as("reversalOfJournalNo"),
                        creator.loginId.as("createdBy"), poster.loginId.as("postedBy"), h.postedAt, h.createdAt))
                .from(h).leftJoin(c).on(c.contractId.eq(h.contractId))
                .leftJoin(rev).on(rev.journalHeaderId.eq(h.reversalOfId))
                .leftJoin(creator).on(creator.userId.eq(h.createdBy))
                .leftJoin(poster).on(poster.userId.eq(h.postedBy))
                .where(searchFilters(from, to, journalType, accountCode, contractId, contractNo, status))
                .orderBy(QuerydslSort.order(sort, SORTABLE, h.journalDate.desc(), h.journalHeaderId.desc()))
                .offset(offset).limit(limit).fetch();
    }

    public long count(LocalDate from, LocalDate to, String journalType,
                      String accountCode, Long contractId, String status) {
        return count(from, to, journalType, accountCode, contractId, null, status);
    }

    public long count(LocalDate from, LocalDate to, String journalType,
                      String accountCode, Long contractId, String contractNo, String status) {
        return queryFactory.select(journalHeader.count()).from(journalHeader)
                .where(searchFilters(from, to, journalType, accountCode, contractId, contractNo, status)).fetchOne();
    }

    /**
     * 미조회 시 기존 상세 조회 계약대로 null을 반환한다.
     * 재기표를 다시 정정해도 자신의 정정그룹을 우선하여 헤더 한 건만 반환한다.
     */
    public JournalDetailHeaderRow findHeaderById(Long journalHeaderId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT h.journal_header_id, h.journal_no, h.journal_date, h.journal_type,
                       h.source_entity_type, h.source_entity_id, h.revision_no,
                       h.contract_id, c.contract_no,
                       h.validation_run_id, h.policy_version_id,
                       COALESCE(h.correction_group_key, original_group.correction_group_key) AS correction_group_key,
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
                  LEFT JOIN fgc.journal_correction_group original_group
                         ON original_group.original_journal_header_id = h.journal_header_id
                  LEFT JOIN fgc.journal_header repost
                         ON repost.correction_group_key =
                            COALESCE(h.correction_group_key, original_group.correction_group_key)
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
        query.addScalar(STATUS_ALIAS, String.class);
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
                    result.setDebitTotal(row.get(DEBIT_TOTAL, BigDecimal.class));
                    result.setCreditTotal(row.get(CREDIT_TOTAL, BigDecimal.class));
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
        query.setParameter(VALIDATION_RUN_ID_PARAMETER, validationRunId);
        query.addScalar("journal_header_id", Long.class);
        query.addScalar("journal_no", String.class);
        query.addScalar("journal_type", String.class);
        query.addScalar(STATUS_ALIAS, String.class);
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
                .setParameter(VALIDATION_RUN_ID_PARAMETER, validationRunId)
                .getSingleResult()).longValue();
    }

    /** DRAFT 및 라인이 없는 헤더도 해당 검증 실행의 전체 건수에 포함한다. */
    public long countJournals(Long validationRunId) {
        return entityManager.createQuery("""
                SELECT COUNT(h) FROM JournalHeader h
                 WHERE h.validationRunId = :validationRunId
                """, Long.class)
                .setParameter(VALIDATION_RUN_ID_PARAMETER, validationRunId)
                .getSingleResult();
    }

    private BooleanBuilder searchFilters(LocalDate from, LocalDate to, String journalType,
                                          String accountCode, Long contractId, String contractNo, String status) {
        var h = journalHeader;
        BooleanBuilder where = new BooleanBuilder();
        if (from != null) where.and(h.journalDate.goe(from));
        if (to != null) where.and(h.journalDate.loe(to));
        // 문자열 조건은 미정의 코드·빈 문자열도 예외 없이 빈 결과를 반환한다.
        if (journalType != null) where.and(h.journalType.stringValue().eq(journalType));
        if (contractId != null) where.and(h.contractId.eq(contractId));
        // 계약번호는 대소문자를 구분하지 않는 부분 일치다. 헤더에 조인을 늘리지 않으려고 서브쿼리로 건다.
        if (contractNo != null && !contractNo.isBlank()) {
            var filterContract = new QInsuranceContract("filterContract");
            where.and(h.contractId.in(JPAExpressions.select(filterContract.contractId).from(filterContract)
                    .where(filterContract.contractNo.containsIgnoreCase(contractNo.trim()))));
        }
        if (status != null) where.and(h.status.stringValue().eq(status));
        if (accountCode != null) where.and(JPAExpressions.selectOne().from(journalLine)
                .join(journalAccount).on(journalAccount.journalAccountId.eq(journalLine.journalAccountId))
                .where(journalLine.journalHeaderId.eq(h.journalHeaderId), journalAccount.accountCode.eq(accountCode)).exists());
        return where;
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
