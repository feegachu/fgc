package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.code.JournalHeaderStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.journal.domain.JournalType;
import com.susukkang.fgc.journal.dto.JournalDetailHeaderRow;
import com.susukkang.fgc.journal.dto.JournalDetailLineResponse;
import com.susukkang.fgc.journal.dto.JournalDetailLineRow;
import com.susukkang.fgc.journal.dto.JournalDetailResponse;
import com.susukkang.fgc.journal.mapper.JournalDetailMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JournalDetailServiceImpl implements JournalDetailService {

    private final JournalDetailMapper journalDetailMapper;

    @Override
    @Transactional(readOnly = true)
    public JournalDetailResponse findByJournalHeaderId(Long journalHeaderId) {
        // 헤더가 없으면 그 ID의 분개가 아예 존재하지 않는다는 뜻이라 404로 던진다.
        JournalDetailHeaderRow row = journalDetailMapper.findHeaderById(journalHeaderId);
        if (row == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", journalHeaderId));
        }

        // Mapper가 line_no 오름차순으로 정렬해서 주므로 여기서 다시 정렬할 필요는 없다.
        List<JournalDetailLineRow> lineRows = journalDetailMapper.findLinesByHeaderId(journalHeaderId);

        List<JournalDetailLineResponse> lines = lineRows.stream()
                .map(JournalDetailLineResponse::from)
                .toList();

        BigDecimal debitTotal = BigDecimal.ZERO;
        BigDecimal creditTotal = BigDecimal.ZERO;
        for (JournalDetailLineRow lineRow : lineRows){
            debitTotal = debitTotal.add(lineRow.getDebitAmount());
            creditTotal = creditTotal.add(lineRow.getCreditAmount());
        }

        BigDecimal differenceAmount = debitTotal.subtract(creditTotal);

        // 차액이 0이라고 곧바로 균형은 아니다 — 라인이 하나도 없는 헤더는 두 합계가 모두
        // 0이라 차액도 0이지만, "차변>0 AND 대변>0"을 만족하지 못해 균형이라 볼 수 없다.
        boolean balanced = debitTotal.compareTo(BigDecimal.ZERO) > 0 &&
                creditTotal.compareTo(BigDecimal.ZERO) > 0 &&
                differenceAmount.compareTo(BigDecimal.ZERO) == 0;

        JournalType journalType = JournalType.valueOf(row.getJournalType());
        JournalHeaderStatus status = JournalHeaderStatus.valueOf(row.getStatus());

        // reversalOfId(이 분개가 역분개일 때 원분개)와 reversedByJournalHeaderId(이 분개가
        // 나중에 역분개당했을 때 그 후속 분개)는 Mapper가 자기참조 조인 2건으로 이미 양쪽 다
        // 채워서 준다 — 여기서는 그대로 옮기기만 하면 된다.
        return new JournalDetailResponse(
                row.getJournalHeaderId(), row.getJournalNo(), row.getJournalDate(),
                journalType, journalType.label(),
                row.getSourceEntityType(), row.getSourceEntityId(), row.getRevisionNo(),
                row.getContractId(), row.getContractNo(),
                row.getValidationRunId(), row.getPolicyVersionId(), row.getCorrectionGroupKey(),
                status, status.label(), row.getDescription(),
                row.getCreatedBy(), row.getCreatedAt(), row.getPostedBy(), row.getPostedAt(),
                row.getReversalOfId(), row.getReversalOfJournalNo(),
                row.getReversedByJournalHeaderId(), row.getReversedByJournalNo(),
                row.getRepostedJournalHeaderId(), row.getRepostedJournalNo(),
                debitTotal, creditTotal, differenceAmount, balanced, lines
        );
    }
}
