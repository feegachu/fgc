package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.journal.dto.JournalBalanceSummary;
import com.susukkang.fgc.journal.mapper.JournalImbalanceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * #98 이슈 To-do "균형 분개 기표 성공, 불균형 기표 거절, 0원 분개 거절 테스트".
 * JournalImbalanceMapper를 모킹하는 순수 단위테스트라 DB가 필요 없다.
 */
@ExtendWith(MockitoExtension.class)
class JournalBalanceValidationServiceImplTest {

    @Mock
    private JournalImbalanceMapper journalImbalanceMapper;

    private JournalBalanceValidationServiceImpl service() {
        return new JournalBalanceValidationServiceImpl(journalImbalanceMapper);
    }

    private static JournalBalanceSummary summaryOf(BigDecimal debit, BigDecimal credit) {
        JournalBalanceSummary summary = new JournalBalanceSummary();
        summary.setJournalHeaderId(1L);
        summary.setDebitTotal(debit);
        summary.setCreditTotal(credit);
        return summary;
    }

    @Test
    void balancedJournalPassesAssertBalancedWithoutException() {
        given(journalImbalanceMapper.findBalanceSummary(1L))
                .willReturn(summaryOf(BigDecimal.valueOf(50_000), BigDecimal.valueOf(50_000)));

        service().assertBalanced(1L);
        // 예외가 안 나면 성공 — 검증할 반환값이 없는 void 메서드다.
    }

    @Test
    void imbalancedJournalIsRejectedWithLedg001AndAmounts() {
        given(journalImbalanceMapper.findBalanceSummary(1L))
                .willReturn(summaryOf(BigDecimal.valueOf(50_000), BigDecimal.valueOf(48_000)));

        assertThatThrownBy(() -> service().assertBalanced(1L))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(ex -> {
                    FgcBusinessException businessException = (FgcBusinessException) ex;
                    assertThat(businessException.getErrorCode()).isEqualTo(FgcErrorCode.LEDG_001);
                    // 이슈 #98 요구사항: "차변·대변 합계 및 차액을 제공합니다" — 차액(c)만이
                    // 아니라 두 합계도 params에 담겨 API 응답에 그대로 노출돼야 한다.
                    assertThat(businessException.getParams())
                            .containsEntry("debitTotal", BigDecimal.valueOf(50_000))
                            .containsEntry("creditTotal", BigDecimal.valueOf(48_000))
                            .containsEntry("c", BigDecimal.valueOf(2_000));
                });
    }

    @Test
    void zeroAmountJournalIsRejectedEvenThoughDebitEqualsCredit() {
        // 차변==대변이어도 둘 다 0이면 균형이 아니다(이슈 #98: "차변 합계가 0보다 큼"
        // 조건이 따로 있다) — 0/0은 애초에 아무 거래도 없다는 뜻이라 "균형 잡힌 분개"로
        // 인정하면 안 된다.
        given(journalImbalanceMapper.findBalanceSummary(1L))
                .willReturn(summaryOf(BigDecimal.ZERO, BigDecimal.ZERO));

        assertThatThrownBy(() -> service().assertBalanced(1L))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(ex -> assertThat(((FgcBusinessException) ex).getErrorCode())
                        .isEqualTo(FgcErrorCode.LEDG_001));
    }

    @Test
    void headerWithNoLinesIsTreatedAsImbalancedInsteadOfThrowingNpe() {
        // journal_line이 하나도 없는 헤더는 Mapper가 null을 돌려준다(GROUP BY 결과 없음).
        // summarize()가 null을 그대로 넘기면 isBalanced() 호출에서 NPE가 났을 것이다.
        given(journalImbalanceMapper.findBalanceSummary(1L)).willReturn(null);

        JournalBalanceSummary summary = service().summarize(1L);
        assertThat(summary.getDebitTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.getCreditTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.isBalanced()).isFalse();

        assertThatThrownBy(() -> service().assertBalanced(1L))
                .isInstanceOf(FgcBusinessException.class);
    }
}
