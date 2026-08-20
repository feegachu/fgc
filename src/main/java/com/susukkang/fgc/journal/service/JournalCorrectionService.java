package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.journal.dto.JournalCorrectionResult;
import com.susukkang.fgc.journal.dto.ReverseAndRepostJournalCommand;
import com.susukkang.fgc.journal.dto.ReverseJournalCommand;
import com.susukkang.fgc.journal.dto.JournalRepostCommand;

/**
 * 설명 : FUN-047 원장 역분개와 재기표 서비스 계약
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
public interface JournalCorrectionService {

    /** IF-API-36 기본 역분개. */
    JournalCorrectionResult reverse(ReverseJournalCommand command);

    /** FUN-047 내부 명령: 역분개와 정정 초안을 같은 트랜잭션에서 재기표한다. */
    JournalCorrectionResult reverseAndRepost(ReverseAndRepostJournalCommand command);

    JournalCorrectionResult reverseAndRepost(JournalRepostCommand command);
}
