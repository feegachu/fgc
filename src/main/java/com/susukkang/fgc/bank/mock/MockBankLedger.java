package com.susukkang.fgc.bank.mock;

import com.susukkang.fgc.bank.repository.BankReconciliationRepository;
import com.susukkang.fgc.bank.repository.BankReconciliationRepository.ExpectedTransfer;
import com.susukkang.fgc.bank.service.BankInquiryStarting;
import com.susukkang.fgc.bank.service.BankTransferMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 설명 : 모의 은행의 수수료 지급 계좌 원장. 실제 은행이 아니라, 재무 담당자가 확정일에 확정 지급대로 이체한 것을 흉내 낸다.
 *
 * - 확정된 달의 이체를 그 달의 대사를 처음 실행할 때 한 번 만들어 저장한다. 그 뒤 FGC의 지급이 바뀌어도 은행 기록은 그대로다.
 * - 대사가 어긋난 건을 잡아내는 것을 보이려고, 받는 사람이 세 명 이상인 달에는 아래 시연용 출금을 만든다.
 *   둘째 사람의 순액이 10,000원을 넘으면 10,000원 덜 보내고, 마지막 사람에게는 보내지 않고, 참조코드 없는 출금 한 건을 섞는다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-18
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "fgc.openbanking.mock.enabled", havingValue = "true")
public class MockBankLedger {

    private static final BigDecimal OPENING_BALANCE = new BigDecimal("500000000");
    private static final BigDecimal SHORT_AMOUNT = new BigDecimal("10000");
    private static final BigDecimal UNKNOWN_AMOUNT = new BigDecimal("55000");
    private static final String UNKNOWN_CONTENT = "관리비";
    private static final LocalTime FIRST_TRANSFER_TIME = LocalTime.of(10, 0);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    private final JdbcTemplate jdbc;
    private final BankReconciliationRepository reconciliations;

    /** 출금 1건과 그 뒤의 잔액. */
    public record Row(LocalDate tranDate, String tranTime, String printContent, BigDecimal amount,
                      BigDecimal afterBalance) {
    }

    private record FinalizedMonth(LocalDate settlementMonth, LocalDate finalizedOn) {
    }

    /** 기간 안의 출금을 오래된 순으로 준다. 읽기만 한다. 조회 요청(GET) 안에서 원장을 만들지 않는다. */
    @Transactional(readOnly = true)
    public List<Row> withdrawals(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT tran_date, tran_time, print_content, tran_amt, after_balance
                  FROM (SELECT tran_date, tran_time, print_content, tran_amt, mock_bank_transaction_id,
                               ? - SUM(tran_amt) OVER (ORDER BY tran_date, tran_time, mock_bank_transaction_id)
                                   AS after_balance
                          FROM fgc.mock_bank_transaction) ledger
                 WHERE tran_date BETWEEN ? AND ?
                 ORDER BY tran_date, tran_time, mock_bank_transaction_id
                """, (rs, row) -> new Row(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5)), OPENING_BALANCE, from, to);
    }

    /**
     * 대사가 은행 조회를 시작한다고 알리면, 그 달이 확정됐고 아직 이체를 만든 적 없을 때 확정 지급대로 이체를 만든다.
     * 대사 실행(POST) 안에서 불리고 조회보다 먼저 커밋된다.
     */
    @EventListener
    @Transactional
    public void recordTransfers(BankInquiryStarting inquiry) {
        List<FinalizedMonth> months = jdbc.query("""
                SELECT vr.validation_month, (vr.finalized_at AT TIME ZONE 'Asia/Seoul')::date
                  FROM fgc.validation_run vr
                 WHERE vr.status = 'FINALIZED' AND vr.run_type = 'MONTHLY' AND vr.validation_month = ?
                   AND NOT EXISTS (SELECT 1 FROM fgc.mock_bank_transaction made
                                    WHERE made.settlement_month = vr.validation_month)
                 ORDER BY vr.validation_run_id DESC
                 LIMIT 1
                """, (rs, row) -> new FinalizedMonth(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)),
                inquiry.settlementMonth());
        for (FinalizedMonth month : months) {
            List<ExpectedTransfer> payees = reconciliations.findExpectedTransfers(month.settlementMonth());
            boolean plantDifferences = payees.size() >= 3;
            int sequence = 0;
            for (int index = 0; index < payees.size(); index++) {
                if (plantDifferences && index == payees.size() - 1) {
                    continue;
                }
                BigDecimal amount = payees.get(index).amount();
                if (plantDifferences && index == 1 && amount.compareTo(SHORT_AMOUNT) > 0) {
                    amount = amount.subtract(SHORT_AMOUNT);
                }
                insert(month, sequence++,
                        BankTransferMatcher.referenceCode(month.settlementMonth(), payees.get(index).agentId()), amount);
            }
            if (plantDifferences) {
                insert(month, sequence, UNKNOWN_CONTENT, UNKNOWN_AMOUNT);
            }
        }
    }

    private void insert(FinalizedMonth month, int sequence, String printContent, BigDecimal amount) {
        // 동시에 두 번 조회돼도 같은 이체를 두 번 만들지 않는다.
        jdbc.update("""
                INSERT INTO fgc.mock_bank_transaction (settlement_month, tran_date, tran_time, print_content, tran_amt)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (settlement_month, print_content) DO NOTHING
                """, month.settlementMonth(), month.finalizedOn(), TIME.format(FIRST_TRANSFER_TIME.plusSeconds(sequence)),
                printContent, amount);
    }
}
