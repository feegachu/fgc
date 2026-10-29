package com.susukkang.fgc.clawback.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateDetailResponse;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateSearchCondition;
import com.susukkang.fgc.common.web.ListSort;
import com.susukkang.fgc.common.web.SqlSort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
              SELECT cc.candidate_id, cc.contract_id, cc.trigger_type, cc.source_id, cc.event_date,
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
                     effects.rows AS effects
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
            ), candidates AS (
              SELECT evidence.*,
                     'claw-v1-' || md5(jsonb_build_array(contract_source, source, payment_source, schedule_source,
                         policies, effects, superseded)::text) AS source_version,
                     -- 승인되지 않은 D-02를 성공 계산/기본 0원으로 바꾸지 않는다.
                     'REVIEW_REQUIRED' AS calculation_status,
                     to_jsonb(array_remove(ARRAY[
                       'CALCULATION_BASIS_UNCONFIRMED',
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
                     ]::text[], NULL)) AS review_reasons
                FROM evidence
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
                'clawbackCaseId', NULL, 'calculatedAmount', NULL, 'allowedActions', '[]'::jsonb),
              'sourceRefs', jsonb_build_array(source_ref), 'paymentAttributions', payments,
              'scheduleLines', schedules, 'policyCandidates', policies, 'effects', effects,
              'elapsedMonths', NULL, 'latestCalculation', NULL) AS text)
              FROM candidates
            """;

    public record SearchResult(List<ClawbackCandidateDetailResponse> items, long total) { }

    public SearchResult search(ClawbackCandidateSearchCondition condition, ListSort sort, int page, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = filters(condition, params);
        List<?> rows = bind(entityManager.createNativeQuery(BASIS + DETAIL + where + " "
                        + SqlSort.orderBy(sort, SORTABLE, "candidate_id DESC")), params)
                .setFirstResult(Math.toIntExact((long) (page - 1) * size)).setMaxResults(size).getResultList();
        long count = ((Number) bind(entityManager.createNativeQuery(BASIS + "SELECT count(*) FROM candidates" + where), params)
                .getSingleResult()).longValue();
        return new SearchResult(rows.stream().map(row -> decode((String) row)).toList(), count);
    }

    public Optional<ClawbackCandidateDetailResponse> findById(long candidateId) {
        List<?> rows = entityManager.createNativeQuery(BASIS + DETAIL + " WHERE candidate_id = :id")
                .setParameter("id", candidateId).getResultList();
        return rows.stream().findFirst().map(row -> decode((String) row));
    }

    private static String filters(ClawbackCandidateSearchCondition condition, Map<String, Object> params) {
        List<String> clauses = new ArrayList<>();
        String date = condition.getDateBasis() == ClawbackCandidateSearchCondition.DateBasis.CONTRACT_DATE
                ? "contract_date" : "event_date";
        add(clauses, params, date + " >= :dateFrom", "dateFrom", condition.getDateFrom());
        add(clauses, params, date + " <= :dateTo", "dateTo", condition.getDateTo());
        if (condition.getContractNo() != null && !condition.getContractNo().isBlank()) {
            String escaped = condition.getContractNo().trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
            add(clauses, params, "contract_no ILIKE :contractNo ESCAPE '!'", "contractNo", "%" + escaped + "%");
        }
        add(clauses, params, "insurer_id = :insurerId", "insurerId", condition.getInsurerId());
        add(clauses, params, "product_offering_id = :productOfferingId", "productOfferingId", condition.getProductOfferingId());
        add(clauses, params, "agent_id = :agentId", "agentId", condition.getAgentId());
        add(clauses, params, "trigger_type = :triggerType", "triggerType",
                condition.getTriggerType() == null ? null : condition.getTriggerType().name());
        add(clauses, params, "calculation_status = :calculationStatus", "calculationStatus",
                condition.getCalculationStatus() == null ? null : condition.getCalculationStatus().name());
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private static void add(List<String> clauses, Map<String, Object> params, String sql, String key, Object value) {
        if (value != null) { clauses.add(sql); params.put(key, value); }
    }

    private static Query bind(Query query, Map<String, Object> params) {
        params.forEach(query::setParameter);
        return query;
    }

    private ClawbackCandidateDetailResponse decode(String json) {
        try {
            return objectMapper.readerFor(ClawbackCandidateDetailResponse.class)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("환수 후보 근거 응답을 변환할 수 없습니다.", e);
        }
    }
}
