package com.susukkang.fgc.clawback.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateDetailResponse;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateSearchCondition;
import com.susukkang.fgc.common.web.ListSort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.hibernate.query.NativeQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 후보·금액·근거를 같은 PostgreSQL snapshot으로 조회한다. LATERAL/JSONB/행별 ROUND 때문에
 * 네이티브 SQL을 사용하며 목록·count는 같은 필터를 공유한다. 조회에서는 UPSERT를 호출하지 않는다.
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClawbackCandidateQueryRepository {
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public static final Map<String, String> SORTABLE = Map.of(
            "candidateId", "candidate_id", "contractNo", "contract_no",
            "eventDate", "event_date", "contractDate", "contract_date",
            "paidAmount", "paid_amount", "unpaidAmount", "unpaid_amount");
    public static final ListSort DEFAULT_SORT = new ListSort("candidateId", false);

    private static final String BASIS = """
            WITH evidence AS (
              SELECT cc.candidate_id, cc.contract_id, cc.trigger_type, cc.source_id, cc.event_date, cc.status_event_id,
                     c.contract_no, c.contract_date, c.insurer_id, i.insurer_name,
                     c.product_offering_id, p.product_name, c.agent_id, ag.agent_name,
                     to_jsonb(c) AS contract_source,
                     CASE WHEN cc.trigger_type = 'STATUS_EVENT' THEN to_jsonb(e) ELSE to_jsonb(a) END AS source,
                     CASE WHEN cc.trigger_type = 'STATUS_EVENT' THEN jsonb_build_object(
                       'triggerType', cc.trigger_type, 'sourceId', cc.source_id,
                       'effectiveAt', e.effective_at, 'asOfDate', NULL, 'status', e.new_status, 'evidence', to_jsonb(e))
                     ELSE jsonb_build_object(
                       'triggerType', cc.trigger_type, 'sourceId', cc.source_id,
                       'effectiveAt', NULL, 'asOfDate', a.as_of_date, 'status', a.result_status, 'evidence', to_jsonb(a))
                     END AS source_ref,
                     a.result_status AS arbitrage_status,
                     EXISTS (SELECT 1 FROM fgc.contract_status_event later
                       WHERE later.contract_id = cc.contract_id AND cc.trigger_type = 'STATUS_EVENT'
                         AND (later.effective_at, later.event_seq, later.contract_status_event_id)
                           > (e.effective_at, e.event_seq, e.contract_status_event_id)
                         AND later.effective_at <= current_timestamp) AS superseded,
                     payments.rows AS payments, payments.raw_rows AS payment_source,
                     CASE WHEN payments.scope_mismatch THEN NULL ELSE payments.paid_amount END AS paid_amount,
                     payments.unassigned_payment, payments.scope_mismatch,
                     schedules.rows AS schedules, schedules.raw_rows AS schedule_source, schedules.line_count,
                     CASE WHEN schedules.line_count = 0 OR payments.unassigned_payment OR payments.scope_mismatch THEN NULL
                          ELSE schedules.unpaid_amount END AS unpaid_amount,
                     policies.rows AS policies, policies.policy_count,
                     CASE WHEN policies.policy_count = 1 THEN policies.policy_id ELSE NULL END AS policy_id,
                     effects.rows AS effects, arbitrage_sources.rows AS arbitrage_source_refs
                FROM fgc.clawback_candidate cc
                JOIN fgc.insurance_contract c ON c.contract_id = cc.contract_id
                JOIN fgc.insurer i ON i.insurer_id = c.insurer_id
                JOIN fgc.product_offering po ON po.product_offering_id = c.product_offering_id
                JOIN fgc.product p ON p.product_id = po.product_id
                JOIN fgc.agent ag ON ag.agent_id = c.agent_id
                LEFT JOIN fgc.contract_status_event e ON e.contract_status_event_id = cc.status_event_id
                  AND e.contract_id = cc.contract_id
                LEFT JOIN fgc.arbitrage_check a ON a.arbitrage_check_id = cc.arbitrage_check_id
                  AND a.contract_id = cc.contract_id AND a.payment_stage = 'GA_TO_FC'
                CROSS JOIN LATERAL (
                  SELECT COALESCE(jsonb_agg(jsonb_build_object(
                           'triggerType', cc.trigger_type, 'sourceId', observed.arbitrage_check_id,
                           'effectiveAt', NULL, 'asOfDate', observed.as_of_date,
                           'status', observed.result_status, 'evidence', to_jsonb(observed))
                           ORDER BY observed.arbitrage_check_id DESC), '[]'::jsonb) AS rows
                    FROM fgc.arbitrage_check observed
                   WHERE cc.status_event_id IS NULL AND observed.contract_id = cc.contract_id
                     AND observed.as_of_date = cc.event_date AND observed.payment_stage = 'GA_TO_FC'
                ) arbitrage_sources
                CROSS JOIN LATERAL (
                  SELECT COALESCE(jsonb_agg(jsonb_build_object(
                           'transactionAttributionId', ta.transaction_attribution_id,
                           'commissionTransactionId', ct.commission_transaction_id,
                           'recipientAgentId', ct.recipient_agent_id, 'commissionItemId', ct.commission_item_id,
                           'installmentNo', COALESCE(ct.installment_no, paid_line.installment_no),
                           'status', ct.status, 'cashflowType', ct.cashflow_type, 'paidOn', ct.paid_on,
                           'attributedAmount', ta.attributed_amount, 'scheduleLineId', ta.schedule_line_id)
                           ORDER BY ta.transaction_attribution_id), '[]'::jsonb) AS rows,
                         COALESCE(jsonb_agg(jsonb_build_array(to_jsonb(ta), to_jsonb(ct))
                           ORDER BY ta.transaction_attribution_id), '[]'::jsonb) AS raw_rows,
                         COALESCE(SUM(ROUND(ta.attributed_amount, 0)) FILTER (
                           WHERE ct.status = 'CONFIRMED' AND ct.cashflow_type = 'PAYMENT'), 0) AS paid_amount,
                         COALESCE(bool_or(ct.status = 'CONFIRMED' AND ct.cashflow_type = 'PAYMENT'
                           AND COALESCE(ct.installment_no, paid_line.installment_no) IS NULL), false) AS unassigned_payment,
                         COALESCE(bool_or(ct.status = 'CONFIRMED' AND ct.cashflow_type = 'PAYMENT' AND (
                           (ct.insurer_id IS NOT NULL AND ct.insurer_id <> c.insurer_id)
                           OR ta.agent_id IS DISTINCT FROM ct.recipient_agent_id)), false) AS scope_mismatch
                    FROM fgc.transaction_attribution ta
                    JOIN fgc.commission_transaction ct ON ct.commission_transaction_id = ta.commission_transaction_id
                    LEFT JOIN fgc.schedule_line paid_line ON paid_line.schedule_line_id = ta.schedule_line_id
                   WHERE ta.contract_id = cc.contract_id AND ta.attribution_scope = 'CONTRACT'
                     AND ct.payment_stage = 'GA_TO_FC'
                ) payments
                CROSS JOIN LATERAL (
                  SELECT COALESCE(jsonb_agg(jsonb_build_object(
                           'scheduleHeaderId', lines.schedule_header_id, 'scheduleLineId', lines.schedule_line_id,
                           'scheduleVersionNo', lines.schedule_version_no, 'beneficiaryAgentId', lines.beneficiary_agent_id,
                           'commissionItemId', lines.commission_item_id, 'installmentNo', lines.installment_no,
                           'dueDate', lines.due_date, 'lineStatus', lines.line_status,
                           'generationReason', lines.generation_reason, 'expectedAmount', lines.expected_amount,
                           'confirmedPaidAmount', lines.confirmed_paid_amount, 'unpaidAmount', lines.unpaid_amount)
                           ORDER BY lines.schedule_line_id), '[]'::jsonb) AS rows,
                         COALESCE(jsonb_agg(lines.raw_source ORDER BY lines.schedule_line_id), '[]'::jsonb) AS raw_rows,
                         COUNT(*) AS line_count, COALESCE(SUM(lines.unpaid_amount), 0) AS unpaid_amount
                  FROM (
                    SELECT sh.schedule_header_id, sl.schedule_line_id, sh.schedule_version_no,
                           COALESCE(sl.beneficiary_agent_id, c.agent_id) AS beneficiary_agent_id,
                           sl.commission_item_id, sl.installment_no, sl.due_date, sl.line_status,
                           sh.generation_reason, ROUND(sl.expected_amount, 0) AS expected_amount,
                           confirmed.amount AS confirmed_paid_amount,
                           jsonb_build_array(to_jsonb(sh), to_jsonb(sl)) AS raw_source,
                           CASE WHEN sl.line_status = 'CANCELLED' THEN 0
                                WHEN payments.unassigned_payment OR payments.scope_mismatch THEN NULL
                                ELSE GREATEST(ROUND(sl.expected_amount, 0) - confirmed.amount, 0) END AS unpaid_amount
                      FROM fgc.schedule_header sh
                      JOIN fgc.schedule_line sl ON sl.schedule_header_id = sh.schedule_header_id
                      CROSS JOIN LATERAL (
                        SELECT COALESCE(SUM(ROUND(ta.attributed_amount, 0)), 0) AS amount
                          FROM fgc.transaction_attribution ta
                          JOIN fgc.commission_transaction ct ON ct.commission_transaction_id = ta.commission_transaction_id
                          LEFT JOIN fgc.schedule_line paid_line ON paid_line.schedule_line_id = ta.schedule_line_id
                         WHERE ta.contract_id = cc.contract_id AND ta.attribution_scope = 'CONTRACT'
                           AND ct.payment_stage = 'GA_TO_FC'
                           AND ct.status = 'CONFIRMED' AND ct.cashflow_type = 'PAYMENT'
                           AND ct.recipient_agent_id = COALESCE(sl.beneficiary_agent_id, c.agent_id)
                           AND ct.commission_item_id = sl.commission_item_id
                           AND COALESCE(ct.installment_no, paid_line.installment_no) = sl.installment_no
                      ) confirmed
                     WHERE sh.contract_id = cc.contract_id AND sh.payment_stage = 'GA_TO_FC'
                       AND sh.active_yn AND sh.schedule_purpose = 'OPERATIONAL' AND sh.status <> 'CANCELLED'
                  ) lines
                ) schedules
                CROSS JOIN LATERAL (
                  SELECT COALESCE(jsonb_agg(jsonb_build_object(
                           'policyVersionId', pv.policy_version_id, 'policyCode', pv.policy_code,
                           'versionNo', pv.version_no, 'policyName', pv.policy_name,
                           'effectiveFrom', application.effective_from, 'effectiveTo', application.application_to)
                           ORDER BY pv.policy_version_id), '[]'::jsonb) AS rows,
                         COUNT(*) AS policy_count, MIN(pv.policy_version_id) AS policy_id
                    FROM fgc.policy_version pv
                    JOIN fgc.policy_application_version application ON application.policy_version_id = pv.policy_version_id
                   WHERE pv.policy_type = 'CLAWBACK'
                     AND pv.approved_at IS NOT NULL AND application.effective_from <= c.contract_date
                     AND (application.application_to IS NULL OR application.application_to >= c.contract_date)
                     AND EXISTS (SELECT 1 FROM fgc.clawback_rule rule
                       WHERE rule.policy_version_id = pv.policy_version_id
                         AND (rule.insurer_id IS NULL OR rule.insurer_id = c.insurer_id)
                         AND (rule.product_offering_id IS NULL OR rule.product_offering_id = c.product_offering_id))
                ) policies
                CROSS JOIN LATERAL (
                  SELECT COALESCE(jsonb_agg(jsonb_build_object(
                           'processingJob', ep.processing_job, 'processingStatus', ep.processing_status,
                           'validationRunId', ep.validation_run_id, 'processedAt', ep.processed_at,
                           'failureReason', ep.failure_reason)
                           ORDER BY ep.processed_at, ep.contract_status_event_processing_id), '[]'::jsonb) AS rows
                    FROM fgc.contract_status_event_processing ep WHERE ep.contract_status_event_id = cc.status_event_id
                ) effects
            ), observed AS (
              SELECT evidence.*,
                     'claw-v1-' || md5(jsonb_build_array(contract_source, source, payment_source, schedule_source,
                         policies, effects, superseded, arbitrage_source_refs)::text) AS source_version,
                     -- D-02 경과월: 계약일부터 사건 기준일까지 채운 개월 수(응당일 당일부터 1개월). 운영정책서 제48조의3.
                     CASE WHEN event_date >= contract_date THEN CAST(
                       date_part('year', age(event_date, contract_date)) * 12
                       + date_part('month', age(event_date, contract_date)) AS integer) END AS elapsed_months,
                     -- 계산을 막는 근거 부족. 정책 선택 필요·스케줄 없음·회차 미연결은 계산 근거(귀속행)와 무관해 막지 않는다.
                     COALESCE(policy_count = 0 OR scope_mismatch OR superseded OR event_date < contract_date
                       OR event_date > CAST(current_timestamp AT TIME ZONE 'Asia/Seoul' AS date)
                       OR (trigger_type = 'ARBITRAGE' AND arbitrage_status IS DISTINCT FROM 'CANDIDATE'), true) AS blocked,
                     to_jsonb(array_remove(ARRAY[
                       CASE WHEN policy_count = 0 THEN 'POLICY_MISSING' END,
                       CASE WHEN policy_count > 1 THEN 'POLICY_SELECTION_REQUIRED' END,
                       CASE WHEN line_count = 0 THEN 'SCHEDULE_MISSING' END,
                       CASE WHEN unassigned_payment THEN 'PAYMENT_INSTALLMENT_MISSING' END,
                       CASE WHEN scope_mismatch THEN 'PAYMENT_SCOPE_MISMATCH' END,
                       CASE WHEN superseded THEN 'STATUS_EVENT_SUPERSEDED' END,
                       CASE WHEN event_date > CAST(current_timestamp AT TIME ZONE 'Asia/Seoul' AS date)
                         THEN 'EVENT_NOT_EFFECTIVE' END,
                       CASE WHEN trigger_type = 'ARBITRAGE' AND arbitrage_status <> 'CANDIDATE'
                         THEN 'ARBITRAGE_SOURCE_CHANGED' END
                     ]::text[], NULL)) AS base_reasons
                FROM evidence
            ), candidates AS (
              SELECT observed.*, latest.clawback_case_id AS case_id, latest.amount AS latest_amount,
                     latest.result_snapshot AS latest_calculation,
                     -- 현재 원천 버전으로 만든 판만 계산완료다. 원천이 바뀐 과거 판은 보여 주되 다시 검토한다.
                     CASE WHEN latest.clawback_calculation_id IS NOT NULL AND latest.source_version = observed.source_version
                               AND NOT observed.blocked THEN 'CALCULATED'
                          WHEN observed.blocked OR latest.clawback_calculation_id IS NOT NULL THEN 'REVIEW_REQUIRED'
                          ELSE 'UNCALCULATED' END AS calculation_status,
                     CASE WHEN latest.clawback_calculation_id IS NOT NULL AND latest.source_version <> observed.source_version
                          THEN observed.base_reasons || '["CALCULATION_STALE"]'::jsonb
                          ELSE observed.base_reasons END AS review_reasons
                FROM observed
                LEFT JOIN LATERAL (
                  SELECT calc.* FROM fgc.clawback_calculation calc
                   WHERE calc.candidate_id = observed.candidate_id
                   ORDER BY calc.version_no DESC LIMIT 1
                ) latest ON true
            )
            """;

    private static final String DETAIL = """
            SELECT CAST(jsonb_build_object(
              'candidate', jsonb_build_object(
                'candidateId', candidate_id, 'contractId', contract_id, 'contractNo', contract_no,
                'insurerId', insurer_id, 'insurerName', insurer_name, 'productOfferingId', product_offering_id,
                'productName', product_name, 'agentId', agent_id, 'agentName', agent_name,
                'triggerType', trigger_type, 'sourceId', source_id, 'eventDate', event_date,
                'dateBasisLabel', CASE WHEN trigger_type = 'ARBITRAGE' THEN '검증기준일' ELSE '효력일' END,
                'contractDate', contract_date, 'sourceVersion', source_version,
                'calculationStatus', calculation_status, 'reviewReasons', review_reasons,
                'paidAmount', paid_amount, 'unpaidAmount', unpaid_amount, 'policyVersionId', policy_id,
                'clawbackCaseId', case_id,
                'calculatedAmount', CASE WHEN calculation_status = 'CALCULATED' THEN latest_amount END,
                -- 역할 판정 전 후보 동작. 처리 권한이 없으면 서비스가 비운다.
                'allowedActions', CASE WHEN NOT blocked AND elapsed_months IS NOT NULL
                                       THEN '["CALCULATE"]'::jsonb ELSE '[]'::jsonb END),
              'sourceRefs', CASE WHEN status_event_id IS NULL THEN arbitrage_source_refs
                                ELSE jsonb_build_array(source_ref) END, 'paymentAttributions', payments,
              'scheduleLines', schedules, 'policyCandidates', policies, 'effects', effects,
              'elapsedMonths', elapsed_months, 'latestCalculation', latest_calculation) AS text)
              FROM candidates
            """;

    public record SearchResult(List<ClawbackCandidateDetailResponse> items, long total) { }

    // 값뿐 아니라 기간/정렬 선택도 바인딩한다. 사용자 입력을 SQL 조각으로 조합하지 않는다.
    private static final String FILTERS = """
             WHERE (CAST(:dateFrom AS date) IS NULL OR
                    CASE WHEN :dateBasis = 'CONTRACT_DATE' THEN contract_date ELSE event_date END >= :dateFrom)
               AND (CAST(:dateTo AS date) IS NULL OR
                    CASE WHEN :dateBasis = 'CONTRACT_DATE' THEN contract_date ELSE event_date END <= :dateTo)
               AND (CAST(:contractNo AS text) IS NULL OR contract_no ILIKE :contractNo ESCAPE '!')
               AND (CAST(:insurerId AS bigint) IS NULL OR insurer_id = :insurerId)
               AND (CAST(:productOfferingId AS bigint) IS NULL OR product_offering_id = :productOfferingId)
               AND (CAST(:agentId AS bigint) IS NULL OR agent_id = :agentId)
               AND (CAST(:triggerType AS text) IS NULL OR trigger_type = :triggerType)
               AND (CAST(:calculationStatus AS text) IS NULL OR calculation_status = :calculationStatus)
            """;

    private static final String ORDER = """
             ORDER BY
               CASE WHEN :sortKey = 'candidateId' AND :ascending THEN candidate_id END ASC NULLS LAST,
               CASE WHEN :sortKey = 'candidateId' AND NOT :ascending THEN candidate_id END DESC NULLS LAST,
               CASE WHEN :sortKey = 'contractNo' AND :ascending THEN contract_no END ASC NULLS LAST,
               CASE WHEN :sortKey = 'contractNo' AND NOT :ascending THEN contract_no END DESC NULLS LAST,
               CASE WHEN :sortKey = 'eventDate' AND :ascending THEN event_date END ASC NULLS LAST,
               CASE WHEN :sortKey = 'eventDate' AND NOT :ascending THEN event_date END DESC NULLS LAST,
               CASE WHEN :sortKey = 'contractDate' AND :ascending THEN contract_date END ASC NULLS LAST,
               CASE WHEN :sortKey = 'contractDate' AND NOT :ascending THEN contract_date END DESC NULLS LAST,
               CASE WHEN :sortKey = 'paidAmount' AND :ascending THEN paid_amount END ASC NULLS LAST,
               CASE WHEN :sortKey = 'paidAmount' AND NOT :ascending THEN paid_amount END DESC NULLS LAST,
               CASE WHEN :sortKey = 'unpaidAmount' AND :ascending THEN unpaid_amount END ASC NULLS LAST,
               CASE WHEN :sortKey = 'unpaidAmount' AND NOT :ascending THEN unpaid_amount END DESC NULLS LAST,
               candidate_id DESC
            """;

    /** 고정 SQL에 조건/정렬을 바인딩해 목록과 동일 조건의 전체 건수를 조회한다. */
    public SearchResult search(ClawbackCandidateSearchCondition condition, ListSort sort, int page, int size) {
        List<?> rows = bindFilters(entityManager.createNativeQuery(BASIS + DETAIL + FILTERS + ORDER), condition)
                .setParameter("sortKey", sort.key(), String.class)
                .setParameter("ascending", sort.ascending(), Boolean.class)
                .setFirstResult(Math.toIntExact((long) (page - 1) * size)).setMaxResults(size).getResultList();
        long count = ((Number) bindFilters(entityManager.createNativeQuery(BASIS + "SELECT count(*) FROM candidates" + FILTERS), condition)
                .getSingleResult()).longValue();
        return new SearchResult(rows.stream().map(row -> decode((String) row)).toList(), count);
    }

    /** 후보를 생성하지 않고 원천 이력·지급/스케줄 근거를 같은 snapshot으로 조회한다. */
    public Optional<ClawbackCandidateDetailResponse> findById(long candidateId) {
        List<?> rows = entityManager.createNativeQuery(BASIS + DETAIL + " WHERE candidate_id = :id")
                .setParameter("id", candidateId).getResultList();
        return rows.stream().findFirst().map(row -> decode((String) row));
    }

    /** null의 JDBC 타입을 지정하고 LIKE 메타문자를 문자로 검색하도록 이스케이프한다. */
    private static NativeQuery<?> bindFilters(Query query, ClawbackCandidateSearchCondition condition) {
        String contractPattern = null;
        if (condition.getContractNo() != null && !condition.getContractNo().isBlank()) {
            String escaped = condition.getContractNo().trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
            contractPattern = "%" + escaped + "%";
        }
        NativeQuery<?> bound = query.unwrap(NativeQuery.class);
        return bound.setParameter("dateFrom", condition.getDateFrom(), java.time.LocalDate.class)
                .setParameter("dateTo", condition.getDateTo(), java.time.LocalDate.class)
                .setParameter("dateBasis", condition.getDateBasis() == ClawbackCandidateSearchCondition.DateBasis.CONTRACT_DATE
                        ? "CONTRACT_DATE" : "EVENT_DATE", String.class)
                .setParameter("contractNo", contractPattern, String.class)
                .setParameter("insurerId", condition.getInsurerId(), Long.class)
                .setParameter("productOfferingId", condition.getProductOfferingId(), Long.class)
                .setParameter("agentId", condition.getAgentId(), Long.class)
                .setParameter("triggerType", condition.getTriggerType() == null ? null : condition.getTriggerType().name(), String.class)
                .setParameter("calculationStatus", condition.getCalculationStatus() == null
                        ? null : condition.getCalculationStatus().name(), String.class);
    }

    /** 원천 근거의 소수 금액도 부동소수점 손실 없이 DTO로 읽는다. */
    private ClawbackCandidateDetailResponse decode(String json) {
        try {
            return objectMapper.readerFor(ClawbackCandidateDetailResponse.class)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("환수 후보 근거 응답을 변환할 수 없습니다.", e);
        }
    }
}
