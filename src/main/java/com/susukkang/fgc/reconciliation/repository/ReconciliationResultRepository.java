package com.susukkang.fgc.reconciliation.repository;

import com.susukkang.fgc.common.web.ListSort;
import com.susukkang.fgc.common.web.SqlSort;
import com.susukkang.fgc.reconciliation.dto.ReconciliationClassificationContext;
import com.susukkang.fgc.reconciliation.dto.ReconciliationMatchDetailRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationMatchInsertRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultDetailRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultInsertRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultListRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationSummaryRow;
import jakarta.persistence.EntityManager;
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
 * 설명 : 대사 결과·원천 연결의 멱등 저장과 분류 문맥·목록·상세·요약 조회.
 * PostgreSQL 원천·집계·잠금 계약을 보존하며 타입 지정 projection으로 조회한다.
 *
 * @author C4t4ddict
 * @since 2026-10-05
 * @version 1.0
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReconciliationResultRepository {
    private static final String CREATED_AT = "createdAt";
    private static final String DIFFERENCE_AMOUNT = "differenceAmount";

    private static final String RECONCILIATION_RESULT_ID_PARAM = "reconciliationResultId";
    private static final String EXPECTED_AGENT_ID_PARAM = "expectedAgentId";
    private static final String ACTUAL_AGENT_ID_PARAM = "actualAgentId";
    private static final String CONTRACT_ID_PARAM = "contractId";
    private static final String RESULT_TYPE_PARAM = "resultType";
    private static final String RECONCILIATION_RUN_ID_PARAM = "reconciliationRunId";
    private static final String VALIDATION_RUN_ID_PARAM = "validationRunId";
    // 2026-10-12 yslee - #518 지급예정일 시점 판정을 현재 상태 조건에서 분리한다(PR #520 리뷰).
    // 기존 코드: 현재 상태가 비유효(해지·실효·만기·청약철회)인 계약만 지급예정일 시점 상태를 봤다.
    // 문제: 실효 뒤 부활(REVIVED)한 계약의 실효 기간 회차에 지급하면 현재 상태 필터에 막혀 비유효로 잡히지 않았다.
    //       예상 원천은 같은 회차를 지급 대상 아님으로 빼므로 두 판정이 어긋났다.
    // 개선: 상태 사건으로 확인된 판정(NON_PAYABLE_ON_DUE_DATE)은 예상 원천과 같은 식을 현재 상태와 무관하게 쓰고,
    //       현재 상태 대체 판정(지급예정일 없음·이력 불신)만 현재 상태가 비유효일 때 적용한다.
    private static final String CLASSIFICATION_CONTEXT_SQL = """
            SELECT EXISTS (SELECT 1 FROM fgc.insurance_contract c
                            CROSS JOIN (SELECT CAST(:dueDate AS date) AS due_date) sl
                            WHERE c.contract_id = :contractId
                              AND (""" + ContractStatusOnDueDateSql.NON_PAYABLE_ON_DUE_DATE + """

                                   OR (c.current_status IN """ + ContractStatusOnDueDateSql.NON_PAYABLE_STATUSES + """

                                       AND (sl.due_date IS NULL
                                            OR """ + ContractStatusOnDueDateSql.LATEST_EVENT_STATUS + """

                                               IS DISTINCT FROM c.current_status))))
                AS invalidContractPayment,
            CASE WHEN CAST(:expectedAgentId AS bigint) IS NULL
                   OR CAST(:actualAgentId AS bigint) IS NULL THEN FALSE
                 ELSE COALESCE((SELECT expected.organization_id <> actual.organization_id
                                  FROM fgc.agent expected, fgc.agent actual
                                 WHERE expected.agent_id = :expectedAgentId
                                   AND actual.agent_id = :actualAgentId), FALSE)
            END AS organizationMismatch,
            (:hasJournals AND (SELECT COUNT(DISTINCT policy_version_id) > 1
                                FROM fgc.journal_header
                               WHERE journal_header_id IN (:journalHeaderIds)
                                 AND policy_version_id IS NOT NULL)) AS policyVersionError,
            (:hasJournals AND EXISTS (SELECT 1 FROM fgc.vw_journal_imbalance
                                      WHERE journal_header_id IN (:journalHeaderIds))) AS journalImbalance,
            EXISTS (SELECT 1 FROM fgc.validation_target vt
                     WHERE vt.validation_run_id = CAST(:validationRunId AS bigint)
                       AND vt.contract_id = :contractId
                       AND vt.selection_status = 'REVIEW_REQUIRED') AS targetReviewRequired
            """;
    private static final List<ReconciliationNativeProjection.Column> RESULT_COLUMNS = List.of(
            new ReconciliationNativeProjection.Column(RECONCILIATION_RESULT_ID_PARAM, Long.class),
            new ReconciliationNativeProjection.Column("contractNo", String.class),
            new ReconciliationNativeProjection.Column("commissionItemCode", String.class),
            new ReconciliationNativeProjection.Column("commissionItemName", String.class),
            new ReconciliationNativeProjection.Column("installmentNo", Integer.class),
            new ReconciliationNativeProjection.Column(EXPECTED_AGENT_ID_PARAM, Long.class),
            new ReconciliationNativeProjection.Column("expectedAgentCode", String.class),
            new ReconciliationNativeProjection.Column("expectedAgentName", String.class),
            new ReconciliationNativeProjection.Column("expectedOrganizationName", String.class),
            new ReconciliationNativeProjection.Column(ACTUAL_AGENT_ID_PARAM, Long.class),
            new ReconciliationNativeProjection.Column("actualAgentCode", String.class),
            new ReconciliationNativeProjection.Column("actualAgentName", String.class),
            new ReconciliationNativeProjection.Column("actualOrganizationName", String.class),
            new ReconciliationNativeProjection.Column("actualSourceAgentCode", String.class),
            new ReconciliationNativeProjection.Column("expectedTotalAmount", BigDecimal.class),
            new ReconciliationNativeProjection.Column("actualTotalAmount", BigDecimal.class),
            new ReconciliationNativeProjection.Column(DIFFERENCE_AMOUNT, BigDecimal.class),
            new ReconciliationNativeProjection.Column(RESULT_TYPE_PARAM, String.class),
            new ReconciliationNativeProjection.Column("primaryReasonCode", String.class),
            new ReconciliationNativeProjection.Column("secondaryReasonCodesCsv", String.class),
            new ReconciliationNativeProjection.Column(CREATED_AT, OffsetDateTime.class));
    private final EntityManager entityManager;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** 충돌은 예외를 발생시키지 않으므로 호출 트랜잭션이 rollback-only가 되지 않는다. */
    @Transactional
    public int insertResult(ReconciliationResultInsertRow row) {
        entityManager.flush();
        NativeQuery<?> query = entityManager.createNativeQuery("""
                INSERT INTO fgc.reconciliation_result (
                    reconciliation_run_id, match_group_key, contract_id, expected_agent_id,
                    actual_agent_id, actual_source_agent_code, commission_item_id, installment_no,
                    result_type, expected_total_amount, actual_total_amount, difference_amount,
                    primary_reason_code, secondary_reason_codes, detail_snapshot
                ) VALUES (
                    :runId, :matchKey, :contractId, :expectedAgentId,
                    :actualAgentId, :sourceAgentCode, :itemId, :installmentNo,
                    :resultType, :expectedAmount, :actualAmount, :differenceAmount,
                    :primaryReason, ARRAY(SELECT jsonb_array_elements_text(CAST(:reasons AS jsonb))),
                    CAST(:snapshot AS jsonb)
                )
                ON CONFLICT ON CONSTRAINT uq_reconciliation_result DO NOTHING
                RETURNING reconciliation_result_id
                """).unwrap(NativeQuery.class);
        query.setParameter("runId", row.getReconciliationRunId());
        query.setParameter("matchKey", row.getMatchGroupKey());
        query.setParameter(CONTRACT_ID_PARAM, row.getContractId());
        query.setParameter(EXPECTED_AGENT_ID_PARAM, row.getExpectedAgentId());
        query.setParameter(ACTUAL_AGENT_ID_PARAM, row.getActualAgentId());
        query.setParameter("sourceAgentCode", row.getActualSourceAgentCode());
        query.setParameter("itemId", row.getCommissionItemId());
        query.setParameter("installmentNo", row.getInstallmentNo());
        query.setParameter(RESULT_TYPE_PARAM, row.getResultType());
        query.setParameter("expectedAmount", row.getExpectedTotalAmount());
        query.setParameter("actualAmount", row.getActualTotalAmount());
        query.setParameter(DIFFERENCE_AMOUNT, row.getDifferenceAmount());
        query.setParameter("primaryReason", row.getPrimaryReasonCode());
        query.setParameter("reasons", writeReasons(row.getSecondaryReasonCodes()));
        query.setParameter("snapshot", row.getDetailSnapshotJson());
        query.addScalar("reconciliation_result_id", Long.class);
        List<?> inserted = query.getResultList();
        row.setReconciliationResultId(inserted.isEmpty() ? null : (Long) inserted.getFirst());
        return inserted.size();
    }

    /** 동일 결과·순번 재입력은 원천 연결과 기존 금액을 덮어쓰지 않는다. */
    @Transactional
    public int insertMatch(ReconciliationMatchInsertRow row) {
        entityManager.flush();
        return entityManager.createNativeQuery("""
                INSERT INTO fgc.reconciliation_match (
                    reconciliation_result_id, match_seq, schedule_line_id,
                    transaction_attribution_id, matched_amount, match_role
                ) VALUES (:resultId, :seq, :lineId, :attributionId, :amount, :role)
                ON CONFLICT ON CONSTRAINT uq_reconciliation_match DO NOTHING
                """)
                .setParameter("resultId", row.reconciliationResultId())
                .setParameter("seq", row.matchSeq())
                .setParameter("lineId", row.scheduleLineId())
                .setParameter("attributionId", row.transactionAttributionId())
                .setParameter("amount", row.matchedAmount())
                .setParameter("role", row.matchRole())
                .executeUpdate();
    }

    /**
     * 빈 ID 목록과 null 참조에서도 SQL 신호가 항상 boolean으로 반환된다.
     * <p>
     * 비유효 계약 지급은 계약의 현재 상태가 아니라 <b>지급예정일 시점</b> 상태로 판정한다
     * (SCHEDULE_ELIGIBILITY {@code STATUS_MONTH_RULE = PAY_IF_ACTIVE_ON_DUE_DATE}, V2). 현재 해지·실효·만기
     * 계약이라도 지급예정일(Asia/Seoul 날짜)까지 효력이 생긴 상태 사건이 비유효 상태가 아니면 정상 지급이다.
     * 다음은 예전처럼 현재 상태만으로 비유효로 본다 — 청약철회(CANCELLED, 처음부터 무효),
     * 지급예정일이 없는 후보, 효력일 기준 최신 상태 사건이 현재 상태와 다른(또는 사건이 없는) 계약 —
     * 계약 수정 화면의 상태 변경은 아직 사건을 남기지 않아(FUN-026 2차) 이력을 믿을 수 없다.
     * "같은 상태 사건이 한 번이라도 있었는가"로 보면 LAPSED→REVIVED 이력 뒤 화면에서 다시 LAPSED로 바꾼
     * 계약이 옛 사건으로 판정돼 비유효 신호가 사라진다(PR #515 리뷰).
     * 이력을 믿을 수 있으면(최신 상태 사건 = 현재 상태) 현재 상태와 무관하게 지급예정일 시점 상태를 본다 —
     * 실효 뒤 부활한 계약의 실효 기간 회차 지급도 비유효다(#518, PR #520 리뷰).
     * <p>
     * #518 — {@code targetReviewRequired}는 이 계약이 이 검증 실행에서 검토필요(REVIEW_REQUIRED)로 보류된
     * 대상인지다. 보류 대상은 스케줄 재생성 등 하위 검증을 받지 않으므로(FUN-042) 예상 내역 없음을
     * 확정 불일치로 단정하지 않는다. 검증 실행에 묶이지 않은 수동 대사(validationRunId NULL)는 항상 FALSE다.
     */
    public ReconciliationClassificationContext findClassificationContext(
            Long validationRunId, Long contractId, Long expectedAgentId, Long actualAgentId,
            List<Long> journalHeaderIds, LocalDate dueDate) {
        boolean hasJournals = journalHeaderIds != null && !journalHeaderIds.isEmpty();
        NativeQuery<?> query = entityManager.createNativeQuery(CLASSIFICATION_CONTEXT_SQL).unwrap(NativeQuery.class);
        query.setParameter(VALIDATION_RUN_ID_PARAM, validationRunId, Long.class);
        query.setParameter(CONTRACT_ID_PARAM, contractId);
        query.setParameter(EXPECTED_AGENT_ID_PARAM, expectedAgentId);
        query.setParameter(ACTUAL_AGENT_ID_PARAM, actualAgentId);
        query.setParameter("dueDate", dueDate, LocalDate.class);
        query.setParameter("hasJournals", hasJournals);
        // 빈 IN 목록을 만들지 않으며 hasJournals=false가 두 신호를 항상 FALSE로 고정한다.
        query.setParameterList("journalHeaderIds", hasJournals ? journalHeaderIds : List.of(0L));
        query.addScalar("invalidcontractpayment", Boolean.class);
        query.addScalar("organizationmismatch", Boolean.class);
        query.addScalar("policyversionerror", Boolean.class);
        query.addScalar("journalimbalance", Boolean.class);
        query.addScalar("targetreviewrequired", Boolean.class);
        return query.setTupleTransformer((values, aliases) -> {
            ReconciliationClassificationContext row = new ReconciliationClassificationContext();
            row.setInvalidContractPayment((Boolean) values[0]);
            row.setOrganizationMismatch((Boolean) values[1]);
            row.setPolicyVersionError((Boolean) values[2]);
            row.setJournalImbalance((Boolean) values[3]);
            row.setTargetReviewRequired((Boolean) values[4]);
            return row;
        }).uniqueResult();
    }

    private String writeReasons(List<String> reasons) {
        try {
            return objectMapper.writeValueAsString(reasons == null ? List.of() : reasons);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("대사 보조 사유를 직렬화하지 못했습니다.", exception);
        }
    }

    public Long findResultId(Long reconciliationRunId, String matchGroupKey) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT reconciliation_result_id
                FROM fgc.reconciliation_result
                WHERE reconciliation_run_id = :reconciliationRunId
                AND match_group_key = :matchGroupKey
                """).unwrap(NativeQuery.class);
        query.setParameter(RECONCILIATION_RUN_ID_PARAM, reconciliationRunId);
        query.setParameter("matchGroupKey", matchGroupKey);
        query.addScalar("reconciliation_result_id", Long.class);
        return (Long) query.uniqueResult();
    }

    public long countByRunId(Long reconciliationRunId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT COUNT(*)
                FROM fgc.reconciliation_result
                WHERE reconciliation_run_id = :reconciliationRunId
                """).unwrap(NativeQuery.class);
        query.setParameter(RECONCILIATION_RUN_ID_PARAM, reconciliationRunId);
        return ((Number) query.getSingleResult()).longValue();
    }

    /** 화면 정렬 key(RECO-W02 결과 목록) → SQL 표현식. 결과 유형·원인은 순서 기준이 없어 제외한다. */
    private static final Map<String, String> RESULT_SORTABLE = Map.of(
            CREATED_AT, "result.created_at",
            "contractNo", "contract.contract_no",
            "commissionItemName", "item.item_name",
            "installmentNo", "result.installment_no",
            "expectedAgentName", "expected_agent.agent_name",
            "actualAgentName", "actual_agent.agent_name",
            "expectedTotalAmount", "result.expected_total_amount",
            "actualTotalAmount", "result.actual_total_amount",
            DIFFERENCE_AMOUNT, "result.difference_amount");
    public static final Set<String> RESULT_SORT_KEYS = Set.copyOf(RESULT_SORTABLE.keySet());
    public static final ListSort DEFAULT_RESULT_SORT = new ListSort(CREATED_AT, false);

    /** 기존 호출 호환: 생성 시각 기준 방향만 고른다. */
    public List<ReconciliationResultListRow> findResults(Long reconciliationRunId, String resultType, String sortDirection, int offset, int limit) {
        return findResults(reconciliationRunId, resultType, new ListSort(CREATED_AT, "asc".equals(sortDirection)), offset, limit);
    }

    public List<ReconciliationResultListRow> findResults(Long reconciliationRunId, String resultType, ListSort sort, int offset, int limit) {
        // 결과 유형·식별자는 바인딩하고 정렬은 허용 목록의 코드 상수 표현식만 쓴다. 동률은 결과 ID가 방향을 따른다.
        String orderBy = SqlSort.orderBy(sort, RESULT_SORTABLE,
                "result.reconciliation_result_id " + (sort.ascending() ? "ASC" : "DESC"));
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT result.reconciliation_result_id AS reconciliationResultId,
                contract.contract_no AS contractNo,
                item.item_code AS commissionItemCode,
                item.item_name AS commissionItemName,
                result.installment_no AS installmentNo,
                expected_agent.agent_id AS expectedAgentId,
                expected_agent.agent_code AS expectedAgentCode,
                expected_agent.agent_name AS expectedAgentName,
                expected_org.organization_name AS expectedOrganizationName,
                actual_agent.agent_id AS actualAgentId,
                actual_agent.agent_code AS actualAgentCode,
                actual_agent.agent_name AS actualAgentName,
                actual_org.organization_name AS actualOrganizationName,
                result.actual_source_agent_code AS actualSourceAgentCode,
                result.expected_total_amount AS expectedTotalAmount,
                result.actual_total_amount AS actualTotalAmount,
                result.difference_amount AS differenceAmount,
                result.result_type AS resultType,
                result.primary_reason_code AS primaryReasonCode,
                array_to_string(result.secondary_reason_codes, ',') AS secondaryReasonCodesCsv,
                result.created_at AS createdAt
                FROM fgc.reconciliation_result result
                LEFT JOIN fgc.insurance_contract contract ON contract.contract_id = result.contract_id
                LEFT JOIN fgc.commission_item item ON item.commission_item_id = result.commission_item_id
                LEFT JOIN fgc.agent expected_agent ON expected_agent.agent_id = result.expected_agent_id
                LEFT JOIN fgc.organization expected_org
                ON expected_org.organization_id = expected_agent.organization_id
                LEFT JOIN fgc.agent actual_agent ON actual_agent.agent_id = result.actual_agent_id
                LEFT JOIN fgc.organization actual_org
                ON actual_org.organization_id = actual_agent.organization_id
                WHERE result.reconciliation_run_id = :reconciliationRunId
                AND (CAST(:resultType AS varchar) IS NULL OR result.result_type = :resultType)
                __order__
                """.replace("__order__", orderBy)).unwrap(NativeQuery.class);
        query.setParameter(RECONCILIATION_RUN_ID_PARAM, reconciliationRunId);
        query.setParameter(RESULT_TYPE_PARAM, resultType);
        query.setFirstResult(offset).setMaxResults(limit);
        return ReconciliationNativeProjection.map(query, ReconciliationResultListRow::new, RESULT_COLUMNS).getResultList();
    }

    public long countResults(Long reconciliationRunId, String resultType) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT COUNT(*)
                FROM fgc.reconciliation_result result
                WHERE result.reconciliation_run_id = :reconciliationRunId
                AND (CAST(:resultType AS varchar) IS NULL OR result.result_type = :resultType)
                """).unwrap(NativeQuery.class);
        query.setParameter(RECONCILIATION_RUN_ID_PARAM, reconciliationRunId);
        query.setParameter(RESULT_TYPE_PARAM, resultType);
        return ((Number) query.getSingleResult()).longValue();
    }

    public ReconciliationSummaryRow findSummary(Long reconciliationRunId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT result_count AS resultCount,
                matched_count AS matchedCount,
                exception_count AS exceptionCount,
                expected_total AS expectedTotal,
                actual_total AS actualTotal,
                difference_total AS differenceTotal
                FROM fgc.vw_reconciliation_summary
                WHERE reconciliation_run_id = :reconciliationRunId
                """).unwrap(NativeQuery.class);
        query.setParameter(RECONCILIATION_RUN_ID_PARAM, reconciliationRunId);
        query.addScalar("resultcount", Long.class);
        query.addScalar("matchedcount", Long.class);
        query.addScalar("exceptioncount", Long.class);
        query.addScalar("expectedtotal", BigDecimal.class);
        query.addScalar("actualtotal", BigDecimal.class);
        query.addScalar("differencetotal", BigDecimal.class);
        return query.setTupleTransformer((values, aliases) -> {
            ReconciliationSummaryRow row = new ReconciliationSummaryRow();
            row.setResultCount((Long) values[0]);
            row.setMatchedCount((Long) values[1]);
            row.setExceptionCount((Long) values[2]);
            row.setExpectedTotal((BigDecimal) values[3]);
            row.setActualTotal((BigDecimal) values[4]);
            row.setDifferenceTotal((BigDecimal) values[5]);
            return row;
        }).uniqueResult();
    }

    public ReconciliationResultDetailRow findDetail(Long reconciliationResultId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT result.reconciliation_result_id AS reconciliationResultId,
                result.reconciliation_run_id AS reconciliationRunId,
                result.match_group_key AS matchGroupKey,
                result.contract_id AS contractId,
                contract.contract_no AS contractNo,
                result.commission_item_id AS commissionItemId,
                item.item_code AS commissionItemCode,
                item.item_name AS commissionItemName,
                result.installment_no AS installmentNo,
                expected_agent.agent_id AS expectedAgentId,
                expected_agent.agent_code AS expectedAgentCode,
                expected_agent.agent_name AS expectedAgentName,
                expected_org.organization_name AS expectedOrganizationName,
                actual_agent.agent_id AS actualAgentId,
                actual_agent.agent_code AS actualAgentCode,
                actual_agent.agent_name AS actualAgentName,
                actual_org.organization_name AS actualOrganizationName,
                result.actual_source_agent_code AS actualSourceAgentCode,
                result.expected_total_amount AS expectedTotalAmount,
                result.actual_total_amount AS actualTotalAmount,
                result.difference_amount AS differenceAmount,
                result.result_type AS resultType,
                result.primary_reason_code AS primaryReasonCode,
                array_to_string(result.secondary_reason_codes, ',') AS secondaryReasonCodesCsv,
                result.detail_snapshot::text AS detailSnapshotJson,
                result.created_at AS createdAt
                FROM fgc.reconciliation_result result
                LEFT JOIN fgc.insurance_contract contract ON contract.contract_id = result.contract_id
                LEFT JOIN fgc.commission_item item ON item.commission_item_id = result.commission_item_id
                LEFT JOIN fgc.agent expected_agent ON expected_agent.agent_id = result.expected_agent_id
                LEFT JOIN fgc.organization expected_org
                ON expected_org.organization_id = expected_agent.organization_id
                LEFT JOIN fgc.agent actual_agent ON actual_agent.agent_id = result.actual_agent_id
                LEFT JOIN fgc.organization actual_org
                ON actual_org.organization_id = actual_agent.organization_id
                WHERE result.reconciliation_result_id = :reconciliationResultId
                """).unwrap(NativeQuery.class);
        query.setParameter(RECONCILIATION_RESULT_ID_PARAM, reconciliationResultId);
        var columns = new java.util.ArrayList<>(RESULT_COLUMNS);
        columns.addAll(List.of(new ReconciliationNativeProjection.Column(RECONCILIATION_RUN_ID_PARAM, Long.class),
                new ReconciliationNativeProjection.Column("matchGroupKey", String.class),
                new ReconciliationNativeProjection.Column(CONTRACT_ID_PARAM, Long.class),
                new ReconciliationNativeProjection.Column("commissionItemId", Long.class),
                new ReconciliationNativeProjection.Column("detailSnapshotJson", String.class)));
        return ReconciliationNativeProjection.map(query, ReconciliationResultDetailRow::new, columns).uniqueResult();
    }

    public List<ReconciliationMatchDetailRow> findMatches(Long reconciliationResultId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT match.match_seq AS matchSeq,
                match.match_role AS matchRole,
                match.schedule_line_id AS scheduleLineId,
                line.schedule_header_id AS scheduleHeaderId,
                match.transaction_attribution_id AS transactionAttributionId,
                transaction.commission_transaction_id AS commissionTransactionId,
                COALESCE(expected_journal.journal_header_id, actual_journal.journal_header_id) AS journalHeaderId,
                COALESCE(schedule.contract_id, attribution.contract_id) AS contractId,
                COALESCE(line.beneficiary_agent_id, attribution.agent_id, transaction.recipient_agent_id) AS agentId,
                COALESCE(line.commission_item_id, transaction.commission_item_id) AS commissionItemId,
                COALESCE(line.installment_no, transaction.installment_no) AS installmentNo,
                line.due_date AS dueDate,
                line.basis_amount AS basisAmount,
                line.rate_pct AS ratePct,
                attribution.attribution_date AS attributionDate,
                transaction.settlement_month AS settlementMonth,
                COALESCE(line.due_date, attribution.attribution_date) AS referenceDate,
                match.matched_amount AS matchedAmount
                FROM fgc.reconciliation_match match
                JOIN fgc.reconciliation_result result
                ON result.reconciliation_result_id = match.reconciliation_result_id
                JOIN fgc.reconciliation_run run
                ON run.reconciliation_run_id = result.reconciliation_run_id
                LEFT JOIN fgc.schedule_line line ON line.schedule_line_id = match.schedule_line_id
                LEFT JOIN fgc.schedule_header schedule ON schedule.schedule_header_id = line.schedule_header_id
                LEFT JOIN fgc.journal_header expected_journal
                ON expected_journal.source_entity_type = 'SCHEDULE_LINE'
                AND expected_journal.source_entity_id = CAST(line.schedule_line_id AS varchar)
                AND expected_journal.status = 'POSTED'
                AND expected_journal.journal_type = CASE run.payment_stage
                WHEN 'INSURER_TO_GA' THEN 'EXPECTED_INSURER_INCOME'
                ELSE 'EXPECTED_FC_PAYOUT'
                END
                LEFT JOIN fgc.transaction_attribution attribution
                ON attribution.transaction_attribution_id = match.transaction_attribution_id
                LEFT JOIN fgc.commission_transaction transaction
                ON transaction.commission_transaction_id = attribution.commission_transaction_id
                LEFT JOIN fgc.journal_header actual_journal
                ON actual_journal.source_entity_type = 'COMMISSION_TRANSACTION'
                AND actual_journal.source_entity_id = CAST(transaction.commission_transaction_id AS varchar)
                AND actual_journal.status = 'POSTED'
                AND actual_journal.journal_type = CASE run.payment_stage
                WHEN 'INSURER_TO_GA' THEN 'ACTUAL_INSURER_STATEMENT'
                ELSE 'CONFIRMED_FC_PAYOUT'
                END
                WHERE match.reconciliation_result_id = :reconciliationResultId
                ORDER BY match.match_seq
                """).unwrap(NativeQuery.class);
        query.setParameter(RECONCILIATION_RESULT_ID_PARAM, reconciliationResultId);
        query.addScalar("matchseq", Integer.class);
        query.addScalar("matchrole", String.class);
        query.addScalar("schedulelineid", Long.class);
        query.addScalar("scheduleheaderid", Long.class);
        query.addScalar("transactionattributionid", Long.class);
        query.addScalar("commissiontransactionid", Long.class);
        query.addScalar("journalheaderid", Long.class);
        query.addScalar("contractid", Long.class);
        query.addScalar("agentid", Long.class);
        query.addScalar("commissionitemid", Long.class);
        query.addScalar("installmentno", Integer.class);
        query.addScalar("duedate", LocalDate.class);
        query.addScalar("basisamount", BigDecimal.class);
        query.addScalar("ratepct", BigDecimal.class);
        query.addScalar("attributiondate", LocalDate.class);
        query.addScalar("settlementmonth", LocalDate.class);
        query.addScalar("referencedate", LocalDate.class);
        query.addScalar("matchedamount", BigDecimal.class);
        return query.setTupleTransformer((values, aliases) -> {
            ReconciliationMatchDetailRow row = new ReconciliationMatchDetailRow();
            row.setMatchSeq((Integer) values[0]);
            row.setMatchRole((String) values[1]);
            row.setScheduleLineId((Long) values[2]);
            row.setScheduleHeaderId((Long) values[3]);
            row.setTransactionAttributionId((Long) values[4]);
            row.setCommissionTransactionId((Long) values[5]);
            row.setJournalHeaderId((Long) values[6]);
            row.setContractId((Long) values[7]);
            row.setAgentId((Long) values[8]);
            row.setCommissionItemId((Long) values[9]);
            row.setInstallmentNo((Integer) values[10]);
            row.setDueDate((LocalDate) values[11]);
            row.setBasisAmount((BigDecimal) values[12]);
            row.setRatePct((BigDecimal) values[13]);
            row.setAttributionDate((LocalDate) values[14]);
            row.setSettlementMonth((LocalDate) values[15]);
            row.setReferenceDate((LocalDate) values[16]);
            row.setMatchedAmount((BigDecimal) values[17]);
            return row;
        }).getResultList();
    }
}
