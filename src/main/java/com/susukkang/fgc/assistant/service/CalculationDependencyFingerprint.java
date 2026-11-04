package com.susukkang.fgc.assistant.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Set;

/** Conservative authoritative dependency set. Unrelated financial changes may invalidate a preview too. */
@Repository
@RequiredArgsConstructor
public class CalculationDependencyFingerprint {
    private final EntityManager entityManager;
    private static final Set<String> OMIT=Set.of("audit_log","ai_call_log","app_user","flyway_schema_history");
    public String current() {
        @SuppressWarnings("unchecked") List<String> tables=entityManager.createNativeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema='fgc' AND table_type='BASE TABLE' ORDER BY table_name").getResultList();
        var business=tables.stream().filter(name->!OMIT.contains(name) && !name.startsWith("batch_") && !name.contains("session") && !name.contains("refresh_token")).toList();
        if(business.isEmpty())throw new IllegalStateException("Business dependency schema is unavailable");
        // Names come exclusively from PostgreSQL metadata, never request data. Only digests leave the query.
        if(business.stream().anyMatch(name->!name.matches("[a-z_][a-z0-9_]*")))throw new IllegalStateException("Unsupported dependency table name");
        String sql=business.stream().map(name->"SELECT '"+name+"' AS name, md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'[]')) AS digest FROM fgc."+name+" t").collect(java.util.stream.Collectors.joining(" UNION ALL "));
        return (String)entityManager.createNativeQuery("SELECT md5(string_agg(name||':'||digest,',' ORDER BY name)) FROM ("+sql+") d").getSingleResult();
    }
}
