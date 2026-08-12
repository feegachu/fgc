package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalHeaderRow;

/**
 * 검증원장 및 분개 라인 저장
 */
public interface JournalPersistenceService {

    /**
     * draft를 저장한다. (journalType, sourceEntityType, sourceEntityId, revisionNo) 조합의
     * 기존 행이 이미 있으면 재삽입하지 않고 그 행을 그대로 반환한다(멱등).
     *
     * @param draft     JournalEntryDraftService가 만든 헤더+라인 초안
     * @param createdBy 저장을 요청한 사용자/배치 주체 (audit_log.user_id로도 쓰인다)
     * @param requestId 저장 성공 시 audit_log.request_id로 남길 요청 식별자
     */
    JournalHeaderRow saveDraft(JournalHeaderDraft draft, Long createdBy, String requestId);
}
