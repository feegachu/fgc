package com.susukkang.fgc.document.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** V58 generated_document 판 이력. append-only이며 storage_key는 이 저장소 밖으로 응답하지 않는다. */
@Repository
@RequiredArgsConstructor
public class GeneratedDocumentRepository {
    private final JdbcTemplate jdbc;

    public record StoredDocument(long documentId, String documentNo, int versionNo, String documentKind,
                                 String sourceEntityType, long sourceEntityId, Integer sourceVersionNo,
                                 String storageKey, String contentHash, long createdBy, OffsetDateTime createdAt) { }

    /** 새로 저장할 판. id는 INSERT가 돌려준다. */
    public record NewDocument(String documentNo, int versionNo, String documentKind, String sourceEntityType,
                              long sourceEntityId, Integer sourceVersionNo, String storageKey, String contentHash,
                              long createdBy, OffsetDateTime createdAt) { }

    private static final String COLUMNS = """
            SELECT generated_document_id, document_no, version_no, document_kind, source_entity_type,
                   source_entity_id, source_version_no, storage_key, content_hash, created_by, created_at
              FROM fgc.generated_document
            """;

    private static final RowMapper<StoredDocument> MAPPER = (rs, n) -> new StoredDocument(rs.getLong(1),
            rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getLong(6),
            rs.getObject(7, Integer.class), rs.getString(8), rs.getString(9), rs.getLong(10),
            rs.getObject(11, OffsetDateTime.class));

    public Optional<StoredDocument> findById(long documentId) {
        return jdbc.query(COLUMNS + " WHERE generated_document_id = ?", MAPPER, documentId).stream().findFirst();
    }

    public Optional<StoredDocument> findVersion(String documentNo, int versionNo) {
        return jdbc.query(COLUMNS + " WHERE document_no = ? AND version_no = ?", MAPPER, documentNo, versionNo)
                .stream().findFirst();
    }

    /** 문서번호의 다음 판. 호출자는 같은 원천을 잠근 트랜잭션에서 부른다(UNIQUE(document_no, version_no)가 최종 방어). */
    public int nextVersion(String documentNo) {
        Integer max = jdbc.queryForObject(
                "SELECT max(version_no) FROM fgc.generated_document WHERE document_no = ?", Integer.class, documentNo);
        return max == null ? 1 : max + 1;
    }

    public long insert(NewDocument d) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.generated_document (document_no, version_no, document_kind, source_entity_type,
                    source_entity_id, source_version_no, storage_key, content_hash, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING generated_document_id
                """, Long.class, d.documentNo(), d.versionNo(), d.documentKind(), d.sourceEntityType(),
                d.sourceEntityId(), d.sourceVersionNo(), d.storageKey(), d.contentHash(), d.createdBy(),
                d.createdAt());
    }

    /** 원천 하나의 발행 이력. 최신 판부터. */
    public List<StoredDocument> findBySource(String documentKind, String sourceEntityType, long sourceEntityId,
                                             int limit, long offset) {
        return jdbc.query(COLUMNS + """
                 WHERE document_kind = ? AND source_entity_type = ? AND source_entity_id = ?
                 ORDER BY version_no DESC, generated_document_id DESC LIMIT ? OFFSET ?
                """, MAPPER, documentKind, sourceEntityType, sourceEntityId, limit, offset);
    }

    public long countBySource(String documentKind, String sourceEntityType, long sourceEntityId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM fgc.generated_document
                 WHERE document_kind = ? AND source_entity_type = ? AND source_entity_id = ?
                """, Long.class, documentKind, sourceEntityType, sourceEntityId);
        return count == null ? 0 : count;
    }
}
