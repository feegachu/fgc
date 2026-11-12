package com.susukkang.fgc.cap.repository;

/** 화면 조회용 cc 별칭 SQL. 미처리 위반을 새 정상 판정으로 숨기지 않는다. 계산 원천에는 사용하지 않는다. */
public final class CapDisplaySql {
    private CapDisplaySql() { }

    public static final String OPEN_VIOLATION = """
            (cc.result_status = 'VIOLATION'
             AND ((cc.candidate_transaction_id IS NULL
                   AND NOT jsonb_exists(cc.calculation_snapshot, 'deletedCandidateTransactionId'))
                  OR EXISTS (SELECT 1 FROM fgc.commission_transaction display_candidate
                              WHERE display_candidate.commission_transaction_id = cc.candidate_transaction_id
                                AND display_candidate.status IN ('DRAFT', 'CONFIRMED')))
             AND EXISTS (SELECT 1 FROM fgc.exception_case display_exception
                          WHERE (display_exception.cap_check_id = cc.cap_check_id
                                 OR (display_exception.source_entity_type = 'CAP_CHECK'
                                     AND display_exception.source_entity_id = cc.cap_check_id::text))
                            AND display_exception.exception_type = 'CAP_VIOLATION'
                            AND display_exception.status IN ('NEW', 'IN_REVIEW')
                            AND COALESCE(display_exception.validation_month,
                                         CAST(date_trunc('month', cc.as_of_date) AS date))
                                = CAST(date_trunc('month', cc.as_of_date) AS date)))
            """;
}
