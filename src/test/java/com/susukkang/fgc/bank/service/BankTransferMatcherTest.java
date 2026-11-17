package com.susukkang.fgc.bank.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.susukkang.fgc.bank.openbanking.BankWithdrawal;
import com.susukkang.fgc.bank.service.BankTransferMatcher.Expected;
import com.susukkang.fgc.bank.service.BankTransferMatcher.Matched;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FUN-090 지급 이체 대사의 판정. 확정 지급 순액과 은행 출금을 통장 표시의 참조코드로 맞춘다. */
class BankTransferMatcherTest {

    private static final LocalDate JULY = LocalDate.of(2026, 7, 1);
    private static final LocalDate PAID_ON = LocalDate.of(2026, 8, 25);

    @Test
    @DisplayName("참조코드는 FGC, 정산월 네 자리, 설계사 번호 여섯 자리다")
    void buildsTheReferenceCode() {
        assertThat(BankTransferMatcher.referenceCode(JULY, 7L)).isEqualTo("FGC2607-000007");
        assertThat(BankTransferMatcher.referenceCode(LocalDate.of(2027, 1, 1), 123_456L)).isEqualTo("FGC2701-123456");
    }

    @Test
    @DisplayName("참조코드가 같고 금액이 같으면 일치다")
    void matchesWhenTheAmountIsEqual() {
        List<Matched> result = BankTransferMatcher.match(JULY, List.of(new Expected(1L, new BigDecimal("890000.00"))),
                List.of(withdrawal("FGC2607-000001", "890000")));

        assertThat(result).containsExactly(new Matched("MATCHED", 1L, "FGC2607-000001",
                new BigDecimal("890000.00"), new BigDecimal("890000"), PAID_ON, "FGC2607-000001"));
    }

    @Test
    @DisplayName("참조코드가 같은데 금액이 다르면 금액 다름이다")
    void flagsADifferentAmount() {
        List<Matched> result = BankTransferMatcher.match(JULY, List.of(new Expected(2L, new BigDecimal("1100000"))),
                List.of(withdrawal("FGC2607-000002", "1090000")));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.result()).isEqualTo("AMOUNT_MISMATCH");
            assertThat(item.expectedAmount()).isEqualByComparingTo("1100000");
            assertThat(item.bankAmount()).isEqualByComparingTo("1090000");
        });
    }

    @Test
    @DisplayName("기대한 이체가 은행에 없으면 이체 없음이다")
    void flagsAMissingTransfer() {
        List<Matched> result = BankTransferMatcher.match(JULY, List.of(new Expected(3L, new BigDecimal("500000"))), List.of());

        assertThat(result).containsExactly(new Matched("NOT_TRANSFERRED", 3L, "FGC2607-000003",
                new BigDecimal("500000"), BigDecimal.ZERO, null, null));
    }

    @Test
    @DisplayName("참조코드가 없는 출금과 기대하지 않은 설계사 앞 출금은 확인 안 된 출금이다")
    void flagsUnknownWithdrawals() {
        List<Matched> result = BankTransferMatcher.match(JULY, List.of(),
                List.of(withdrawal("관리비", "55000"), withdrawal("FGC2607-000009", "70000")));

        assertThat(result).containsExactly(
                new Matched("UNKNOWN_WITHDRAWAL", null, null, BigDecimal.ZERO, new BigDecimal("70000"), PAID_ON, "FGC2607-000009"),
                new Matched("UNKNOWN_WITHDRAWAL", null, null, BigDecimal.ZERO, new BigDecimal("55000"), PAID_ON, "관리비"));
    }

    @Test
    @DisplayName("다른 정산월의 참조코드가 붙은 출금은 이 달 대사에서 뺀다")
    void ignoresTransfersOfOtherMonths() {
        List<Matched> result = BankTransferMatcher.match(JULY, List.of(new Expected(1L, new BigDecimal("890000"))),
                List.of(withdrawal("FGC2606-000001", "300000"), withdrawal("FGC2607-000001", "890000")));

        assertThat(result).singleElement().satisfies(item -> assertThat(item.result()).isEqualTo("MATCHED"));
    }

    @Test
    @DisplayName("같은 참조코드로 나눠 보낸 출금은 합쳐서 비교하고 마지막 출금일을 남긴다")
    void sumsSplitTransfers() {
        List<Matched> result = BankTransferMatcher.match(JULY, List.of(new Expected(1L, new BigDecimal("890000"))),
                List.of(new BankWithdrawal(PAID_ON, "100001", "FGC2607-000001", new BigDecimal("400000")),
                        new BankWithdrawal(PAID_ON.plusDays(1), "100002", "FGC2607-000001", new BigDecimal("490000"))));

        assertThat(result).containsExactly(new Matched("MATCHED", 1L, "FGC2607-000001",
                new BigDecimal("890000"), new BigDecimal("890000"), PAID_ON.plusDays(1), "FGC2607-000001"));
    }

    @Test
    @DisplayName("어긋난 것을 먼저, 일치를 마지막에 둔다")
    void listsMismatchesFirst() {
        List<Matched> result = BankTransferMatcher.match(JULY,
                List.of(new Expected(1L, new BigDecimal("100")), new Expected(2L, new BigDecimal("200")),
                        new Expected(3L, new BigDecimal("300"))),
                List.of(withdrawal("FGC2607-000001", "100"), withdrawal("FGC2607-000002", "190"),
                        withdrawal("관리비", "55")));

        assertThat(result).extracting(Matched::result)
                .containsExactly("AMOUNT_MISMATCH", "NOT_TRANSFERRED", "UNKNOWN_WITHDRAWAL", "MATCHED");
    }

    private static BankWithdrawal withdrawal(String printContent, String amount) {
        return new BankWithdrawal(PAID_ON, "100001", printContent, new BigDecimal(amount));
    }
}
