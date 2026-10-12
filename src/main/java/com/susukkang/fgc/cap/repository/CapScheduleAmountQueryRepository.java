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
    // CapCalculatorImpl이 반환 순서대로 detail_seq를 매기므로 결정적 정렬이 필요하다(#501).
    // CAP-W02 ③ "항목별 산입 내역"은 같은 항목을 모아 월차 순으로 둔다(FUN-035, 화면정의서 CAP-W02 "③ 표 정렬").
    // 항목 사이 순서는 commission_item_id(등록 순)일 뿐 업무 규칙이 아니다 — 규정·협회 자료에 표시 순서가 없다(#507).
    private static final String BASE_SQL = """
        SELECT sl.schedule_line_id      AS scheduleLineId,
               sl.commission_item_id     AS commissionItemId,
               sl.contract_month_no      AS contractMonthNo,
               sl.expected_amount        AS amount,
               (
                   WITH RECURSIVE preserved_lines AS (
                       SELECT sl.schedule_line_id, sl.schedule_header_id
                       UNION
                       SELECT previous.schedule_line_id, previous.schedule_header_id
                         FROM preserved_lines lineage
                         JOIN fgc.schedule_line copied ON copied.schedule_line_id = lineage.schedule_line_id
                         JOIN fgc.schedule_header copied_header ON copied_header.schedule_header_id = copied.schedule_header_id
                         JOIN fgc.schedule_header previous_header ON previous_header.schedule_header_id = copied_header.regenerated_from_id
                         JOIN fgc.schedule_line previous ON previous.schedule_header_id = previous_header.schedule_header_id
                        WHERE copied.line_status IN ('CONFIRMED', 'MATCHED', 'ADJUSTED')
                          AND previous.line_status IN ('CONFIRMED', 'MATCHED', 'ADJUSTED')
                          AND previous_header.contract_id = sh.contract_id
                          AND previous_header.payment_stage = sh.payment_stage
                          AND previous_header.schedule_purpose = 'OPERATIONAL'
                          AND ROW(previous.installment_no, previous.contract_month_no, previous.due_date,
                                  previous.commission_item_id, previous.beneficiary_agent_id,
                                  previous.basis_code, previous.basis_amount, previous.calculation_type,
                                  previous.rate_pct, previous.fixed_amount, previous.expected_amount,
                                  previous.rounding_scale, previous.rounding_mode, previous.payment_condition_code,
                                  previous.source_commission_rule_id)
                              IS NOT DISTINCT FROM
                              ROW(copied.installment_no, copied.contract_month_no, copied.due_date,
                                  copied.commission_item_id, copied.beneficiary_agent_id,
                                  copied.basis_code, copied.basis_amount, copied.calculation_type,
                                  copied.rate_pct, copied.fixed_amount, copied.expected_amount,
                                  copied.rounding_scale, copied.rounding_mode, copied.payment_condition_code,
                                  copied.source_commission_rule_id)
                   )
                   SELECT CASE
                              WHEN COUNT(ta.transaction_attribution_id) > 0
                               AND BOOL_AND(NULLIF(BTRIM(ta.evidence_ref), '') IS NOT NULL)
                                  THEN MIN(NULLIF(BTRIM(ta.evidence_ref), ''))
                          END
                     FROM fgc.transaction_attribution ta
                     JOIN fgc.commission_transaction ct
                       ON ct.commission_transaction_id = ta.commission_transaction_id
                    WHERE ta.schedule_line_id IN (SELECT schedule_line_id FROM preserved_lines)
                      AND ta.contract_id = sh.contract_id
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
         ORDER BY sl.commission_item_id, sl.contract_month_no, sl.schedule_line_id
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
