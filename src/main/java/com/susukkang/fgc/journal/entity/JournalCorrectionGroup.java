package com.susukkang.fgc.journal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 설명 : 원분개와 역분개·재기표를 연결하는 불변 정정그룹 엔티티
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-28
 */
@Entity
@Table(name = "journal_correction_group", schema = "fgc", uniqueConstraints = {
        @UniqueConstraint(name = "uq_journal_correction_original", columnNames = "original_journal_header_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JournalCorrectionGroup {

    @Id
    @Column(name = "correction_group_key", nullable = false, length = 80, updatable = false)
    private String correctionGroupKey;

    @Column(name = "original_journal_header_id", nullable = false, updatable = false)
    private Long originalJournalHeaderId;

    @Column(name = "reason", nullable = false, length = 1000, updatable = false)
    private String reason;

    @Column(name = "evidence_ref", length = 500, updatable = false)
    private String evidenceRef;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private JournalCorrectionGroup(String correctionGroupKey, Long originalJournalHeaderId,
                                   String reason, String evidenceRef, Long createdBy) {
        this.correctionGroupKey = correctionGroupKey;
        this.originalJournalHeaderId = originalJournalHeaderId;
        this.reason = reason;
        this.evidenceRef = evidenceRef;
        this.createdBy = createdBy;
    }
}
