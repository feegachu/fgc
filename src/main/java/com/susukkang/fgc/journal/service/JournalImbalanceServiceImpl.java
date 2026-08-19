package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.journal.dto.JournalImbalanceSearchResponse;
import com.susukkang.fgc.journal.dto.LedgerImbalanceRow;
import com.susukkang.fgc.journal.mapper.JournalImbalanceMapper;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JournalImbalanceServiceImpl implements JournalImbalanceService {

    private final JournalImbalanceMapper journalImbalanceMapper;
    private final ValidationRunMapper validationRunMapper;

    @Override
    @Transactional(readOnly = true)
    public JournalImbalanceSearchResponse findImbalances(Long validationRunId) {
        if (validationRunMapper.findById(validationRunId) == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", validationRunId));
        }

        // 목록과 건수를 같은 결과에서 계산한다 — 페이징이 없는 API라 별도 COUNT 쿼리를
        // 또 던지면 그 사이 커밋된 변경 때문에 rows.size()와 totalCount가 어긋날 수 있다
        // (기본 READ_COMMITTED에서 두 SELECT가 서로 다른 스냅샷을 볼 수 있음, 코드리뷰 반영).
        List<LedgerImbalanceRow> rows = journalImbalanceMapper.findImbalances(validationRunId);

        return JournalImbalanceSearchResponse.of(rows, rows.size());
    }
}
