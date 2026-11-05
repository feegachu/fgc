package com.susukkang.fgc.assistant.service;

import jakarta.persistence.EntityManager;
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
}
