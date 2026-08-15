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

        List<LedgerImbalanceRow> rows = journalImbalanceMapper.findImbalances(validationRunId);
        long totalCount = journalImbalanceMapper.countImbalances(validationRunId);

        return JournalImbalanceSearchResponse.of(rows, totalCount);
    }
}
