package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalHeaderRow;

/**
 * #93 검증원장 및 분개 라인 저장. JournalEntryDraftService(#85)가 만든
 * JournalHeaderDraft를 journal_header/journal_line에 DRAFT 상태로 저장한다.
 *
 * 차변·대변 균형검사(POSTED 전환 시점) 및 실제 POSTED 전이는 이 이슈 범위 밖이다 —
 * guard_journal_header_write 트리거가 status DRAFT→POSTED UPDATE 시점에 균형을
 * 강제하므로(V1__baseline_v2_1_2.sql:1349-1357), 이 서비스는 항상 DRAFT로만 INSERT한다.
 */
public interface JournalPersistenceService {

    /**
     * draft를 저장한다. (journalType, sourceEntityType, sourceEntityId, revisionNo) 조합의
     * 기존 행이 이미 있으면 재삽입하지 않고 그 행을 그대로 반환한다(멱등).
     *
     * @param draft     JournalEntryDraftService가 만든 헤더+라인 초안
     * @param createdBy 저장을 요청한 사용자/배치 주체 (audit_log.user_id로도 쓰인다)
     * @param requestId 저장 성공 시 audit_log.request_id로 남길 요청 식별자(FUN-046 감사
     *                   요구사항 — "원천·분개 ID·검증 실행 ID·요청 ID를 포함한 감사 로그")
     */
    JournalHeaderRow saveDraft(JournalHeaderDraft draft, Long createdBy, String requestId);
}
