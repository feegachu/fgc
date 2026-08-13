package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.journal.dto.JournalBalanceSummary;
import com.susukkang.fgc.journal.mapper.JournalImbalanceMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service
public class JournalBalanceValidationServiceImpl implements JournalBalanceValidationService {

    private final JournalImbalanceMapper journalImbalanceMapper;

    public JournalBalanceValidationServiceImpl(JournalImbalanceMapper journalImbalanceMapper) {
        this.journalImbalanceMapper = journalImbalanceMapper;
    }

    @Override
    public JournalBalanceSummary summarize(Long journalHeaderId) {
        JournalBalanceSummary summary = journalImbalanceMapper.findBalanceSummary(journalHeaderId);
        if (summary == null) {
            // journal_line이 하나도 없는 헤더는 GROUP BY 결과 자체가 없어 Mapper가 null을
            // 돌려준다(findBalanceSummary Javadoc 참고) — 0/0으로 채워서 돌려주면
            // isBalanced()가 자동으로 false가 되어(차변합계>0 조건 불만족) 호출부가 null
            // 체크를 따로 안 해도 된다.
            summary = new JournalBalanceSummary();
            summary.setJournalHeaderId(journalHeaderId);
            summary.setDebitTotal(BigDecimal.ZERO);
            summary.setCreditTotal(BigDecimal.ZERO);
        }
        return summary;
    }

    @Override
    public void assertBalanced(Long journalHeaderId) {
        JournalBalanceSummary summary = summarize(journalHeaderId);
        if (!summary.isBalanced()) {
            // 이슈 #98 요구사항: "FGC-LEDG-001 오류 코드와 함께 차변·대변 합계 및 차액을
            // 제공합니다" — 메시지 템플릿(error.ledger.imbalance) 자체는 {c}만 쓰지만,
            // ApiError.params는 응답 JSON에 그대로 노출되므로(ApiError 참고) 차변·대변
            // 합계도 같이 담아야 클라이언트가 두 값을 따로 보여줄 수 있다.
            throw new FgcBusinessException(FgcErrorCode.LEDG_001, Map.of(
                    "debitTotal", summary.getDebitTotal(),
                    "creditTotal", summary.getCreditTotal(),
                    "c", summary.differenceAmount()));
        }
    }
}
