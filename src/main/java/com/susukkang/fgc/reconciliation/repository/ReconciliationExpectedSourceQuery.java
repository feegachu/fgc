package com.susukkang.fgc.reconciliation.repository;

import com.susukkang.fgc.common.code.PaymentStage;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import static com.susukkang.fgc.reconciliation.repository.ReconciliationNativeProjection.Column;

/**
 * 설명 : 양방향 예상 원천 조회의 공통 필터와 단계별 수취인·분개 조건을 보존한다.
 *
 * @author C4t4ddict
 * @since 2026-10-05
 * @version 1.0
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ReconciliationExpectedSourceQuery {
    private final EntityManager entityManager;
    private static final List<Column> COLUMNS = List.of(
            new Column("scheduleLineId", Long.class), new Column("journalHeaderId", Long.class),
            new Column("contractId", Long.class), new Column("expectedAgentId", Long.class),
            new Column("commissionItemId", Long.class), new Column("installmentNo", Integer.class),
            new Column("dueDate", LocalDate.class), new Column("dueMonth", LocalDate.class),
            new Column("expectedAmount", BigDecimal.class));

    <T> List<T> find(LocalDate month, Long insurerId, PaymentStage stage, Supplier<T> factory) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT sl.schedule_line_id AS scheduleLineId,
                       jh.journal_header_id AS journalHeaderId,
                       sh.contract_id AS contractId,
                       CASE WHEN :stage = 'GA_TO_FC' THEN sl.beneficiary_agent_id
                            ELSE c.agent_id END AS expectedAgentId,
                       sl.commission_item_id AS commissionItemId,
                       sl.installment_no AS installmentNo, sl.due_date AS dueDate,
                       sl.due_month AS dueMonth, sl.expected_amount AS expectedAmount
                  FROM fgc.schedule_header sh
                  JOIN fgc.schedule_line sl ON sl.schedule_header_id = sh.schedule_header_id
                  JOIN fgc.insurance_contract c ON c.contract_id = sh.contract_id
                  LEFT JOIN fgc.journal_header jh
                    ON jh.journal_type = CASE WHEN :stage = 'GA_TO_FC' THEN 'EXPECTED_FC_PAYOUT'
                                             ELSE 'EXPECTED_INSURER_INCOME' END
                   AND jh.source_entity_type = 'SCHEDULE_LINE'
                   AND jh.source_entity_id = CAST(sl.schedule_line_id AS varchar)
                   AND jh.status = 'POSTED'
                 WHERE sh.payment_stage = :stage
                   AND (:stage = 'GA_TO_FC' OR jh.journal_header_id IS NOT NULL)
                   AND sh.schedule_purpose = 'OPERATIONAL' AND sh.active_yn = TRUE
                   AND sh.status NOT IN ('HOLD', 'CANCELLED')
                   AND sl.line_status NOT IN ('HOLD', 'CANCELLED')
                   AND sl.due_month = :settlementMonth AND c.insurer_id = :insurerId
                 ORDER BY sh.contract_id, sl.commission_item_id, sl.due_month,
                          sl.installment_no, sl.schedule_line_id
                """).unwrap(NativeQuery.class);
        query.setParameter("stage", stage.name());
        query.setParameter("settlementMonth", month);
        query.setParameter("insurerId", insurerId);
        return ReconciliationNativeProjection.map(query, factory, COLUMNS).getResultList();
    }
}
