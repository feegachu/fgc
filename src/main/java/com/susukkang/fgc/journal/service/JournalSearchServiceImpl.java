package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.journal.dto.JournalListRow;
import com.susukkang.fgc.journal.dto.JournalSearchCriteria;
import com.susukkang.fgc.journal.mapper.JournalSearchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JournalSearchServiceImpl implements JournalSearchService {

    private final JournalSearchMapper journalSearchMapper;

    private static final int MIN_PAGE = 1;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<JournalListRow> search(JournalSearchCriteria criteria, int page, int size) {
        if(page < MIN_PAGE){
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "page", Map.of("field", "page"), null);
        }
        if(size < MIN_SIZE || size > MAX_SIZE){
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "size", Map.of("field", "size"), null);
        }

        long offLong = (long) (page-1)*size;
        if(offLong > Integer.MAX_VALUE){
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "page", Map.of("field", "page"), null);
        }
        int offset = (int) offLong;

        List<JournalListRow> rows = journalSearchMapper.search(
                criteria.from(), criteria.to(), criteria.journalType(), criteria.accountCode(),
                criteria.contractId(), criteria.status(), offset, size
        );

        Long total = journalSearchMapper.count(criteria.from(), criteria.to(), criteria.journalType(),
                criteria.accountCode(), criteria.contractId(), criteria.status());

        return PageResponse.of(rows, page, size, total, "journalDate,desc");
    }
}
