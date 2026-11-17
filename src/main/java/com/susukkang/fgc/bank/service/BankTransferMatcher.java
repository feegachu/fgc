package com.susukkang.fgc.bank.service;

import com.susukkang.fgc.bank.openbanking.BankWithdrawal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 설명 : FUN-090 지급 이체 대사의 판정. 은행 거래내역에는 상대 계좌번호가 없어, 이체할 때 내 통장 표시에 적는
 * 참조코드(FGC + 정산월 yyMM + - + 설계사 번호 6자리)로 확정 지급과 출금을 맞춘다(운영정책서 제36조의2, 가상 GA 정책).
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-18
 */
public final class BankTransferMatcher {

    static final String MATCHED = "MATCHED";
    static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    static final String NOT_TRANSFERRED = "NOT_TRANSFERRED";
    static final String UNKNOWN_WITHDRAWAL = "UNKNOWN_WITHDRAWAL";

    private static final Pattern REFERENCE = Pattern.compile("^FGC\\d{4}-\\d{6}$");
    /** 사람이 볼 것부터 보이게 어긋난 것을 앞에 둔다. */
    private static final List<String> ORDER = List.of(AMOUNT_MISMATCH, NOT_TRANSFERRED, UNKNOWN_WITHDRAWAL, MATCHED);

    /** 설계사 1명에게 그 정산월에 이체해야 할 금액(확정 지급 순액, 0보다 큼). */
    record Expected(Long agentId, BigDecimal amount) {
    }

    record Matched(String result, Long agentId, String referenceCode, BigDecimal expectedAmount,
                   BigDecimal bankAmount, LocalDate bankTranDate, String printContent) {
    }

    private BankTransferMatcher() {
    }

    /** 이체할 때 내 통장 표시에 적는 값. 통장인자내용 한도(20바이트) 안이다. */
    public static String referenceCode(LocalDate settlementMonth, Long agentId) {
        return "FGC%02d%02d-%06d".formatted(settlementMonth.getYear() % 100, settlementMonth.getMonthValue(), agentId);
    }

    static List<Matched> match(LocalDate settlementMonth, List<Expected> expected, List<BankWithdrawal> withdrawals) {
        Map<String, Expected> expectedByReference = new LinkedHashMap<>();
        for (Expected transfer : expected) {
            expectedByReference.put(referenceCode(settlementMonth, transfer.agentId()), transfer);
        }
        String monthPrefix = referenceCode(settlementMonth, 0L).substring(0, 8);
        Map<String, BigDecimal> paid = new HashMap<>();
        Map<String, LocalDate> paidOn = new HashMap<>();
        List<Matched> items = new ArrayList<>();
        for (BankWithdrawal withdrawal : withdrawals) {
            String content = withdrawal.printContent();
            boolean reference = REFERENCE.matcher(content).matches();
            if (reference && !content.startsWith(monthPrefix)) {
                // 다른 정산월의 이체다. 그 달의 대사에서 본다.
                continue;
            }
            if (reference && expectedByReference.containsKey(content)) {
                paid.merge(content, withdrawal.amount(), BigDecimal::add);
                paidOn.merge(content, withdrawal.tranDate(), (left, right) -> left.isAfter(right) ? left : right);
            } else {
                items.add(new Matched(UNKNOWN_WITHDRAWAL, null, null, BigDecimal.ZERO, withdrawal.amount(),
                        withdrawal.tranDate(), content));
            }
        }
        expectedByReference.forEach((reference, transfer) -> {
            BigDecimal bank = paid.getOrDefault(reference, BigDecimal.ZERO);
            boolean transferred = bank.signum() != 0;
            String result = !transferred ? NOT_TRANSFERRED
                    : bank.compareTo(transfer.amount()) == 0 ? MATCHED : AMOUNT_MISMATCH;
            items.add(new Matched(result, transfer.agentId(), reference, transfer.amount(), bank,
                    paidOn.get(reference), transferred ? reference : null));
        });
        items.sort(Comparator.comparingInt((Matched item) -> ORDER.indexOf(item.result()))
                .thenComparing(item -> item.referenceCode() != null ? item.referenceCode() : item.printContent()));
        return items;
    }
}
