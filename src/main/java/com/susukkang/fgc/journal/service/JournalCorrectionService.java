package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.journal.dto.JournalCorrectionResult;
import com.susukkang.fgc.journal.dto.ReverseAndRepostJournalCommand;
import com.susukkang.fgc.journal.dto.ReverseJournalCommand;

public interface JournalCorrectionService {

    /** IF-API-36 기본 역분개. */
    JournalCorrectionResult reverse(ReverseJournalCommand command);

    /** FUN-047 내부 명령: 역분개와 정정 초안을 같은 트랜잭션에서 재기표한다. */
    JournalCorrectionResult reverseAndRepost(ReverseAndRepostJournalCommand command);
}
