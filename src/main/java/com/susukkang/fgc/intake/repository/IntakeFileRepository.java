package com.susukkang.fgc.intake.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** #637 저장소 메타데이터만 담당. 문서 상태·추출·확정은 #640/#641 범위다. */
@Repository
@RequiredArgsConstructor
public class IntakeFileRepository {
    private final EntityManager em;

    public void lockHash(String hash) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:key, 637))")
                .setParameter("key", hash).getSingleResult();
    }

    public Optional<Long> findIdByHash(String hash) {
        var rows = em.createNativeQuery("SELECT intake_document_id FROM fgc.intake_document WHERE content_hash = :hash")
                .setParameter("hash", hash).getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(((Number) rows.getFirst()).longValue());
    }

    public long insert(String name, String key, String mediaType, long size, String hash, long userId) {
        return ((Number) em.createNativeQuery("""
                INSERT INTO fgc.intake_document
                  (original_file_name, storage_key, media_type, file_size_bytes, content_hash, uploaded_by)
                VALUES (:name, :key, :media, :size, :hash, :userId)
                RETURNING intake_document_id
                """).setParameter("name", name).setParameter("key", key).setParameter("media", mediaType)
                .setParameter("size", size).setParameter("hash", hash).setParameter("userId", userId)
                .getSingleResult()).longValue();
    }

    public Optional<SourceFile> findSource(long id) {
        var rows = em.createNativeQuery("""
                SELECT intake_document_id, storage_key, media_type, original_file_name, false
                FROM fgc.intake_document WHERE intake_document_id = :id AND source_purged_at IS NULL FOR SHARE
                """).setParameter("id", id).getResultList();
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(source((Object[]) rows.getFirst()));
    }

    /** 한 트랜잭션에서 잠근 원본만 파기한다. 원본을 사용하는 워커도 같은 행 잠금을 사용해야 한다. */
    public List<SourceFile> lockExpired(LocalDate today, int limit) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT intake_document_id, storage_key, media_type, original_file_name, source_purged_at IS NOT NULL
                FROM fgc.intake_document
                WHERE purge_after <= :today AND status <> 'EXTRACTING' AND source_deleted_at IS NULL
                ORDER BY purge_after, intake_document_id LIMIT :limit FOR UPDATE SKIP LOCKED
                """).setParameter("today", today).setParameter("limit", limit).getResultList();
        return rows.stream().map(IntakeFileRepository::source).toList();
    }

    private static SourceFile source(Object[] row) {
        return new SourceFile(((Number) row[0]).longValue(), (String) row[1], (String) row[2], (String) row[3], (Boolean) row[4]);
    }

    public void markPurge(long id) {
        em.createNativeQuery("UPDATE fgc.intake_document SET source_purged_at = clock_timestamp() WHERE intake_document_id = :id AND source_purged_at IS NULL")
                .setParameter("id", id).executeUpdate();
    }

    public void confirmDeletion(long id) {
        em.createNativeQuery("UPDATE fgc.intake_document SET source_deleted_at = clock_timestamp() WHERE intake_document_id = :id AND source_purged_at IS NOT NULL AND source_deleted_at IS NULL")
                .setParameter("id", id).executeUpdate();
    }

    public boolean lockPendingDeletion(long id) {
        return !em.createNativeQuery("""
                SELECT intake_document_id FROM fgc.intake_document WHERE intake_document_id = :id
                AND source_purged_at IS NOT NULL AND source_deleted_at IS NULL FOR UPDATE SKIP LOCKED
                """).setParameter("id", id).getResultList().isEmpty();
    }

    public record SourceFile(long documentId, String storageKey, String mediaType, String originalName, boolean purged) { }
}
