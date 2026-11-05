package com.susukkang.fgc.assistant.service;

import jakarta.persistence.EntityManager;
import com.susukkang.fgc.assistant.dto.PaymentCalculationRequest;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** Conservative authoritative dependency set. Unrelated financial changes may invalidate a preview too. */
@Repository
@RequiredArgsConstructor
public class CalculationDependencyFingerprint {
    private final EntityManager entityManager;

    // No request value or Java-composed SQL is executed. PostgreSQL quotes metadata identifiers with %I.
    // query_to_xml returns only a per-table SHA-256 digest; source rows remain inside the database.
    private static final String DEPENDENCIES = """
        WITH business AS (
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'fgc' AND table_type = 'BASE TABLE'
              AND table_name NOT IN ('audit_log', 'ai_call_log', 'app_user', 'flyway_schema_history')
              AND table_name NOT LIKE 'batch\\_%' ESCAPE '\\'
              AND table_name NOT LIKE '%session%'
              AND table_name NOT LIKE '%refresh_token%'
        )
        SELECT CASE WHEN count(*) = 0 THEN NULL ELSE
            encode(sha256(convert_to(string_agg(table_name || ':' ||
                CAST(query_to_xml(format($fingerprint$
                    SELECT encode(sha256(convert_to(COALESCE(
                        string_agg(row_digest, ',' ORDER BY row_digest), ''), 'UTF8')), 'hex') AS digest
                    FROM (
                        SELECT encode(sha256(convert_to(CAST(to_jsonb(t) AS text), 'UTF8')), 'hex') AS row_digest
                        FROM %I.%I t
                    ) rows
                $fingerprint$, 'fgc', table_name), true, false, '') AS text), ',' ORDER BY table_name),
                'UTF8')), 'hex') END
        FROM business
        """;

    public String current() {
        String fingerprint = (String) entityManager.createNativeQuery(DEPENDENCIES).setHint("jakarta.persistence.query.timeout", 5000).getSingleResult();
        if (fingerprint == null) throw new IllegalStateException("Business dependency schema is unavailable");
        return fingerprint;
    }

    // Only trusted SQL fragments are selected here; request values are bound and quoted by PostgreSQL.
    // Unknown business tables and shared policy rows remain conservative dependencies.
    private static final String SCOPED_DEPENDENCIES = """
        WITH initial_contracts AS (
            SELECT unnest(CAST(:contractIds AS bigint[])) AS id
            UNION SELECT contract_id FROM fgc.transaction_attribution WHERE commission_transaction_id = :paymentId
            UNION SELECT contract_id FROM fgc.reconciliation_result WHERE reconciliation_result_id = :resultId
        ), roots AS (
            SELECT COALESCE(array_agg(DISTINCT id ORDER BY id) FILTER (WHERE id IS NOT NULL), ARRAY[]::bigint[]) AS ids
            FROM initial_contracts
        ), payments AS (
            SELECT COALESCE(array_agg(DISTINCT commission_transaction_id ORDER BY commission_transaction_id), ARRAY[]::bigint[]) AS ids
            FROM (
                SELECT commission_transaction_id FROM fgc.transaction_attribution, roots WHERE contract_id = ANY(roots.ids)
                UNION SELECT CAST(:paymentId AS bigint) WHERE :paymentId > 0
            ) selected
        ), business AS (
            SELECT t.table_name, array_agg(c.column_name) AS columns
            FROM information_schema.tables t JOIN information_schema.columns c
              ON c.table_schema = t.table_schema AND c.table_name = t.table_name
            WHERE t.table_schema = 'fgc' AND t.table_type = 'BASE TABLE'
              AND t.table_name NOT IN ('audit_log','ai_call_log','app_user','app_role','flyway_schema_history',
                  'notice','inquiry','inquiry_action','library_item','knowledge_chunk','agent_contract_check')
              AND t.table_name NOT LIKE 'batch\\_%' ESCAPE '\\'
              AND t.table_name NOT LIKE '%session%' AND t.table_name NOT LIKE '%refresh_token%'
            GROUP BY t.table_name
        ), predicates AS (
            SELECT table_name, CASE
                WHEN cardinality(roots.ids) = 0 THEN 'true'
                WHEN table_name = 'commission_transaction' THEN format('t.commission_transaction_id = ANY(%L::bigint[])', payments.ids)
                WHEN table_name = 'transaction_attribution' THEN format('t.contract_id = ANY(%L::bigint[]) OR t.commission_transaction_id = ANY(%L::bigint[])', roots.ids, payments.ids)
                WHEN 'contract_id' = ANY(columns) THEN format('t.contract_id IS NULL OR t.contract_id = ANY(%L::bigint[])', roots.ids)
                WHEN 'schedule_header_id' = ANY(columns) THEN format('t.schedule_header_id IN (SELECT schedule_header_id FROM fgc.schedule_header WHERE contract_id = ANY(%L::bigint[]))', roots.ids)
                WHEN 'cap_check_id' = ANY(columns) THEN format('t.cap_check_id IN (SELECT cap_check_id FROM fgc.cap_check WHERE contract_id = ANY(%L::bigint[]))', roots.ids)
                WHEN 'reconciliation_result_id' = ANY(columns) THEN format('t.reconciliation_result_id IN (SELECT reconciliation_result_id FROM fgc.reconciliation_result WHERE contract_id = ANY(%L::bigint[]))', roots.ids)
                WHEN 'commission_transaction_id' = ANY(columns) THEN format('t.commission_transaction_id = ANY(%L::bigint[])', payments.ids)
                ELSE 'true' END AS predicate
            FROM business CROSS JOIN roots CROSS JOIN payments
        )
        SELECT CASE WHEN count(*) = 0 THEN NULL ELSE
            encode(sha256(convert_to(string_agg(table_name || ':' ||
                CAST(query_to_xml(format($fingerprint$
                    SELECT encode(sha256(convert_to(COALESCE(string_agg(row_digest, ',' ORDER BY row_digest), ''), 'UTF8')), 'hex') AS digest
                    FROM (SELECT encode(sha256(convert_to(CAST(to_jsonb(t) AS text), 'UTF8')), 'hex') AS row_digest
                          FROM %I.%I t WHERE %s) rows
                $fingerprint$, 'fgc', table_name, predicate), true, false, '') AS text), ',' ORDER BY table_name), 'UTF8')), 'hex') END
        FROM predicates
        """;

    public String current(PaymentCalculationRequest request) {
        var contracts = new TreeSet<Long>();
        var scope = request.scope();
        if (scope != null && scope.contractId() != null) contracts.add(Long.parseLong(scope.contractId()));
        if (request.candidate() != null) {
            var rows = request.candidate().path("payment").path("attributions");
            if (rows.isArray()) for (var row : rows) {
                var id = row.path("contractId");
                if (!id.isMissingNode() && !id.isNull()) contracts.add(Long.parseLong(id.asText()));
            }
        }
        String ids = "{" + contracts.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(",")) + "}";
        String fingerprint = (String) entityManager.createNativeQuery(SCOPED_DEPENDENCIES)
                .setParameter("contractIds", ids)
                .setParameter("paymentId", scope == null || scope.paymentId() == null ? 0L : Long.parseLong(scope.paymentId()))
                .setParameter("resultId", scope == null || scope.reconciliationResultId() == null ? 0L : Long.parseLong(scope.reconciliationResultId()))
                .setHint("jakarta.persistence.query.timeout", 5000).getSingleResult();
        if (fingerprint == null) throw new IllegalStateException("Business dependency schema is unavailable");
        return fingerprint;
    }

    public String forPayment(long paymentId) {
        return current(new PaymentCalculationRequest("assistant.v1", PaymentCalculationRequest.Kind.PAYMENT_CANDIDATE,
                PaymentCalculationRequest.Mode.EXISTING, new PaymentCalculationRequest.Scope(Long.toString(paymentId)), null, 0L));
    }
}
