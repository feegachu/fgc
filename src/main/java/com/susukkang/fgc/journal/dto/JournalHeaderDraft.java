package com.susukkang.fgc.journal.dto;

import com.susukkang.fgc.journal.domain.JournalType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * journal_header + journal_line 초안. journal_no는 포함하지 않는다 — 채번은 영속화
 * 단계(후속 이슈)에서 UNIQUE 제약(uq_journal_no)을 지키며 결정할 몫이다.
 *
 * (journalType, sourceEntityType, sourceEntityId, revisionNo) 조합은
 * uq_journal_source_revision과 같은 멱등키다 — 영속화 서비스는 이 4개 값으로 기존 초안
 * 존재 여부를 조회해야 한다. revisionNo는 이 서비스가 항상 1로 채운다 — 정정·재기표
 * (revision 증가)는 FUN-047의 몫이라 #85 범위 밖이다.
 */
@Getter
@Builder
public class JournalHeaderDraft {
    private final JournalType journalType;
    private final LocalDate journalDate;
    private final String sourceEntityType;
    private final String sourceEntityId;
    private final int revisionNo;
    private final Long validationRunId;
    private final Long contractId;
    private final Long policyVersionId;
    private final String description;
    private final List<JournalLineDraft> lines;
}
