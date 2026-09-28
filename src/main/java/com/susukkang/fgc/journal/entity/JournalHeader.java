package com.susukkang.fgc.journal.entity;

import com.susukkang.fgc.common.code.JournalHeaderStatus;
import com.susukkang.fgc.journal.domain.JournalType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 설명 : 내부 검증원장 분개 헤더 엔티티
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Entity
@Table(
        name = "journal_header",
        schema = "fgc",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_journal_no", columnNames = "journal_no"),
                @UniqueConstraint(
                        name = "uq_journal_source_revision",
                        columnNames = {"journal_type", "source_entity_type", "source_entity_id", "revision_no"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JournalHeader {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "journal_header_id", nullable = false)
    private Long journalHeaderId;

    @Column(name = "journal_no", nullable = false, length = 80)
    private String journalNo;

    @Column(name = "journal_date", nullable = false)
    private LocalDate journalDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "journal_type", nullable = false, length = 35)
    private JournalType journalType;

    @Column(name = "source_entity_type", nullable = false, length = 60)
    private String sourceEntityType;

    @Column(name = "source_entity_id", nullable = false, length = 100)
    private String sourceEntityId;

    @Column(name = "revision_no", nullable = false)
    private Integer revisionNo = 1;

    @Column(name = "validation_run_id")
    private Long validationRunId;

    @Column(name = "contract_id")
    private Long contractId;

    @Column(name = "policy_version_id")
    private Long policyVersionId;

    @Column(name = "reversal_of_id")
    private Long reversalOfId;

    @Column(name = "correction_group_key", length = 80)
    private String correctionGroupKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private JournalHeaderStatus status = JournalHeaderStatus.DRAFT;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "posted_by")
    private Long postedBy;

    // 기표 시각은 DB 트리거가 관리한다.
    @Column(name = "posted_at", insertable = false, updatable = false)
    private OffsetDateTime postedAt;

    // 생성 시각은 DB 기본값을 사용한다.
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    // 신규 분개는 DRAFT로 생성한다. 식별자와 생성·기표 시각은 DB가 관리한다.
    @Builder
    private JournalHeader(String journalNo, LocalDate journalDate, JournalType journalType,
                          String sourceEntityType, String sourceEntityId, Integer revisionNo,
                          Long validationRunId, Long contractId, Long policyVersionId,
                          Long reversalOfId, String correctionGroupKey, String description, Long createdBy) {
        this.journalNo = journalNo;
        this.journalDate = journalDate;
        this.journalType = journalType;
        this.sourceEntityType = sourceEntityType;
        this.sourceEntityId = sourceEntityId;
        this.revisionNo = revisionNo;
        this.validationRunId = validationRunId;
        this.contractId = contractId;
        this.policyVersionId = policyVersionId;
        this.reversalOfId = reversalOfId;
        this.correctionGroupKey = correctionGroupKey;
        this.description = description;
        this.createdBy = createdBy;
    }
}
