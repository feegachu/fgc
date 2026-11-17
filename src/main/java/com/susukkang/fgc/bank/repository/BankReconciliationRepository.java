package com.susukkang.fgc.bank.repository;

import com.susukkang.fgc.bank.dto.BankReconciliationResponse;
import com.susukkang.fgc.bank.dto.BankReconciliationResponse.Item;
import com.susukkang.fgc.common.util.DateUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 설명 : 지급 이체 대사의 기준값 조회와 결과 저장(bank_reconciliation_run·item). 모든 값은 바인딩한다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-18
 */
@Repository
@RequiredArgsConstructor
public class BankReconciliationRepository {

    private final JdbcTemplate jdbc;

    /** 그 달을 확정한 실행과 확정한 날(서울 기준). */
    public record FinalizedRun(Long validationRunId, LocalDate finalizedOn) {
    }

    /** 설계사 1명에게 그 정산월에 이체해야 할 금액. */
    public record ExpectedTransfer(Long agentId, BigDecimal amount) {
    }

    public Optional<FinalizedRun> findFinalizedRun(LocalDate settlementMonth) {
        return jdbc.query("""
                SELECT validation_run_id, (finalized_at AT TIME ZONE 'Asia/Seoul')::date
                  FROM fgc.validation_run
                 WHERE validation_month = ? AND status = 'FINALIZED'
                 ORDER BY validation_run_id DESC
                 LIMIT 1
                """, (rs, row) -> new FinalizedRun(rs.getLong(1), rs.getObject(2, LocalDate.class)), settlementMonth)
                .stream().findFirst();
    }

    /**
     * 설계사별 확정 지급(GA_TO_FC) 합계에서 확정 차감 합계를 뺀 금액. 원 단위로 반올림하고 0보다 큰 것만 준다.
     * 원천징수는 FGC가 다루지 않으므로 빼지 않는다(운영정책서 제36조의2, 가상 GA 정책).
     */
    public List<ExpectedTransfer> findExpectedTransfers(LocalDate settlementMonth) {
        return jdbc.query("""
                SELECT recipient_agent_id,
                       ROUND(SUM(CASE WHEN cashflow_type = 'PAYMENT' THEN amount ELSE -amount END), 0) AS net
                  FROM fgc.commission_transaction
                 WHERE payment_stage = 'GA_TO_FC' AND status = 'CONFIRMED' AND settlement_month = ?
                 GROUP BY recipient_agent_id
                HAVING ROUND(SUM(CASE WHEN cashflow_type = 'PAYMENT' THEN amount ELSE -amount END), 0) > 0
                 ORDER BY recipient_agent_id
                """, (rs, row) -> new ExpectedTransfer(rs.getLong(1), rs.getBigDecimal(2)), settlementMonth);
    }

    public Long insertRun(LocalDate settlementMonth, Long validationRunId, LocalDate inquiryFrom, LocalDate inquiryTo,
                          String bankName, String accountNumMasked, int withdrawalCount, Long createdBy) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.bank_reconciliation_run
                       (settlement_month, validation_run_id, inquiry_from, inquiry_to, bank_name, account_num_masked,
                        withdrawal_count, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING bank_reconciliation_run_id
                """, Long.class, settlementMonth, validationRunId, inquiryFrom, inquiryTo, bankName, accountNumMasked,
                withdrawalCount, createdBy);
    }

    public void insertItem(Long runId, String result, Long agentId, String referenceCode, BigDecimal expectedAmount,
                           BigDecimal bankAmount, LocalDate bankTranDate, String printContent) {
        jdbc.update("""
                INSERT INTO fgc.bank_reconciliation_item
                       (bank_reconciliation_run_id, result, agent_id, reference_code, expected_amount, bank_amount,
                        bank_tran_date, print_content)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, result, agentId, referenceCode, expectedAmount, bankAmount, bankTranDate, printContent);
    }

    /** 그 달에 마지막으로 돌린 대사. */
    public Optional<BankReconciliationResponse> findLatest(LocalDate settlementMonth) {
        return jdbc.query("""
                SELECT max(bank_reconciliation_run_id) FROM fgc.bank_reconciliation_run WHERE settlement_month = ?
                """, (rs, row) -> rs.getObject(1, Long.class), settlementMonth)
                .stream().filter(java.util.Objects::nonNull).findFirst().flatMap(this::findById);
    }

    public Optional<BankReconciliationResponse> findById(Long runId) {
        List<Item> items = jdbc.query("""
                SELECT i.result, a.agent_code, a.agent_name, i.reference_code, i.expected_amount, i.bank_amount,
                       i.bank_amount - i.expected_amount, i.bank_tran_date, i.print_content
                  FROM fgc.bank_reconciliation_item i
                  LEFT JOIN fgc.agent a ON a.agent_id = i.agent_id
                 WHERE i.bank_reconciliation_run_id = ?
                 ORDER BY i.bank_reconciliation_item_id
                """, (rs, row) -> new Item(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getObject(8, LocalDate.class),
                rs.getString(9)), runId);
        return jdbc.query("""
                SELECT r.bank_reconciliation_run_id, r.settlement_month, r.inquiry_from, r.inquiry_to, r.bank_name,
                       r.account_num_masked, r.withdrawal_count, u.login_id, r.created_at
                  FROM fgc.bank_reconciliation_run r
                  JOIN fgc.app_user u ON u.user_id = r.created_by
                 WHERE r.bank_reconciliation_run_id = ?
                """, (rs, row) -> new BankReconciliationResponse(rs.getLong(1), rs.getObject(2, LocalDate.class),
                rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class), rs.getString(5), rs.getString(6),
                rs.getInt(7), rs.getString(8), DateUtil.toSeoul(rs.getObject(9, OffsetDateTime.class)),
                count(items, "MATCHED"), count(items, "AMOUNT_MISMATCH"), count(items, "NOT_TRANSFERRED"),
                count(items, "UNKNOWN_WITHDRAWAL"), items), runId).stream().findFirst();
    }

    private static int count(List<Item> items, String result) {
        return (int) items.stream().filter(item -> result.equals(item.result())).count();
    }
}
