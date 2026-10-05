package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.dto.ScheduleAmountView;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 설명 : 현재 운영 스케줄의 초년도 예상 금액과 연결된 확정 제외 증빙을 조회한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-10-02
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CapScheduleAmountQueryRepository {

    private final EntityManager entityManager;

    // 해당 귀속행이 있고 모두 증빙을 갖춘 경우에만 증빙을 반환한다. 증빙이 없어도 스케줄 행은 조회한다.
    private static final String BASE_SQL = """
        SELECT sl.schedule_line_id      AS scheduleLineId,
               sl.commission_item_id     AS commissionItemId,
               sl.contract_month_no      AS contractMonthNo,
               sl.expected_amount        AS amount,
               (
                   SELECT CASE
                              WHEN COUNT(ta.transaction_attribution_id) > 0
                               AND BOOL_AND(NULLIF(BTRIM(ta.evidence_ref), '') IS NOT NULL)
                                  THEN MIN(NULLIF(BTRIM(ta.evidence_ref), ''))
                          END
                     FROM fgc.transaction_attribution ta
                     JOIN fgc.commission_transaction ct
                       ON ct.commission_transaction_id = ta.commission_transaction_id
                    WHERE ta.schedule_line_id = sl.schedule_line_id
                      AND ct.status = 'CONFIRMED'
                      AND ct.payment_stage = sh.payment_stage
                      AND ct.commission_item_id = sl.commission_item_id
                      AND ta.inclusion_status_snapshot = 'EXCLUDED'
               ) AS evidenceRef
          FROM fgc.schedule_line sl
          JOIN fgc.schedule_header sh ON sh.schedule_header_id = sl.schedule_header_id
         WHERE sh.contract_id = :contractId
           AND sh.payment_stage = :paymentStage
           AND sh.schedule_purpose = 'OPERATIONAL'
           AND sh.active_yn = true
           AND sl.line_status <> 'CANCELLED'
           AND sl.contract_month_no BETWEEN 1 AND :firstYearMonths
        """;

    /** 분급 체계와 관계없이 계약월차 1~firstYearMonths의 현재 운영 스케줄을 조회한다. */
    public List<ScheduleAmountView> findFirstYearScheduleAmounts(
            Long contractId, String paymentStage, int firstYearMonths
    ) {
        NativeQuery<?> query = entityManager.createNativeQuery(BASE_SQL)
                .unwrap(NativeQuery.class);
        query.setParameter("contractId", contractId, Long.class);
        query.setParameter("paymentStage", paymentStage, String.class);
        query.setParameter("firstYearMonths", firstYearMonths, Integer.class);
        query.addScalar("scheduleLineId", Long.class);
        query.addScalar("commissionItemId", Long.class);
        query.addScalar("contractMonthNo", Integer.class);
        query.addScalar("amount", BigDecimal.class);
        query.addScalar("evidenceRef", String.class);
        return query.setTupleTransformer((tuple, aliases) -> {
            ScheduleAmountView row = new ScheduleAmountView();
            row.setScheduleLineId((Long) tuple[0]);
            row.setCommissionItemId((Long) tuple[1]);
            row.setContractMonthNo((Integer) tuple[2]);
            row.setAmount((BigDecimal) tuple[3]);
            row.setEvidenceRef((String) tuple[4]);
            return row;
        }).getResultList();
    }
}
