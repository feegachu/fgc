package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.journal.dto.JournalListRow;
import com.susukkang.fgc.journal.dto.JournalSearchCriteria;

/**
 * 검증원장 목록 조회(GET /api/v1/journals). LEDG-W01 화면의 검색 결과를 페이징해 돌려준다.
 */
public interface JournalSearchService {

    /**
     * criteria로 검증원장을 검색한다.
     *
     * @throws com.susukkang.fgc.common.exception.FgcBusinessException
     *         (COMMON_002) page&lt;1이거나 size가 1~100 범위를 벗어날 때
     */
    PageResponse<JournalListRow> search(JournalSearchCriteria criteria, int page, int size);
}
