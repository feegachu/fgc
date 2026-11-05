package com.susukkang.fgc.journal.repository;

import com.susukkang.fgc.common.code.JournalHeaderStatus;
import com.susukkang.fgc.journal.domain.JournalType;
import com.susukkang.fgc.journal.dto.JournalHeaderRow;
import com.susukkang.fgc.journal.entity.JournalHeader;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 설명 : 원장 헤더 저장 및 원천별 멱등 조회·채번 Repository
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-28
 */
public interface JournalHeaderRepository
        extends JpaRepository<JournalHeader, Long> {

    // 조건부 벌크 상태 변경 후에도 영속성 컨텍스트의 이전 상태 대신 DB 값을 DTO로 투영한다.
    @Query("""
            SELECT new com.susukkang.fgc.journal.dto.JournalHeaderRow(
                h.journalHeaderId, h.journalNo, h.journalDate, cast(h.journalType as string),
                h.sourceEntityType, h.sourceEntityId, h.revisionNo,
                h.validationRunId, h.contractId, h.policyVersionId,
                cast(h.status as string), h.description, h.createdBy, h.postedBy,
                h.postedAt, h.createdAt
            )
            FROM JournalHeader h
            WHERE h.journalType = :journalType
              AND h.sourceEntityType = :sourceEntityType
              AND h.sourceEntityId = :sourceEntityId
              AND h.revisionNo = :revisionNo
            """)
    Optional<JournalHeaderRow> findBySourceKey(
            @Param("journalType") JournalType journalType,
            @Param("sourceEntityType") String sourceEntityType,
            @Param("sourceEntityId") String sourceEntityId,
            @Param("revisionNo") Integer revisionNo
    );

    boolean existsByJournalTypeAndSourceEntityTypeAndSourceEntityIdAndStatus(
            JournalType journalType, String sourceEntityType, String sourceEntityId, JournalHeaderStatus status
    );

    // 라인을 먼저 flush하여 DB 트리거가 저장된 차변·대변으로 기표 가능 여부를 검증하게 한다.
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE JournalHeader h
               SET h.status = com.susukkang.fgc.common.code.JournalHeaderStatus.POSTED,
                   h.postedBy = :postedBy
             WHERE h.journalHeaderId = :journalHeaderId
               AND h.status = com.susukkang.fgc.common.code.JournalHeaderStatus.DRAFT
            """)
    int markPosted(@Param("journalHeaderId") Long journalHeaderId, @Param("postedBy") Long postedBy);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE JournalHeader h
               SET h.status = com.susukkang.fgc.common.code.JournalHeaderStatus.REVERSED
             WHERE h.journalHeaderId = :journalHeaderId
               AND h.status = com.susukkang.fgc.common.code.JournalHeaderStatus.POSTED
            """)
    int markReversed(@Param("journalHeaderId") Long journalHeaderId);

    // V82 부분 인덱스의 끝값 한 건으로 MAX를 구한다. 정규식이 인덱스 조건과 글자까지 같아야 인덱스가 쓰인다.
    @Query(value = """
            SELECT COALESCE(MAX(CAST(substring(journal_no FROM 12) AS int)), 0) + 1
              FROM fgc.journal_header
             WHERE left(journal_no, 10) = 'JV-' || :year || '-' || :month
               AND journal_no ~ '^JV-[0-9]{4}-[0-9]{2}-[0-9]{1,9}$'
            """, nativeQuery = true)
    Integer findNextJournalSeq(@Param("year") String year, @Param("month") String month);
}
