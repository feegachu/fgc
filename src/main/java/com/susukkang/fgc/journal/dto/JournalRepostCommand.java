package com.susukkang.fgc.journal.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 원분개 메타데이터를 서버에서 계승해 역분개와 재기표를 수행하는 외부 입력 명령
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
public record JournalRepostCommand(
        Long journalHeaderId,
        String reason,
        String evidenceRef,
        Long requestedBy,
        LocalDate journalDate,
        String description,
        List<JournalRepostLineCommand> lines
) {
}
