package com.susukkang.fgc.cap.repository;

/** 화면 조회용 cc 별칭 SQL. 미처리 위반을 새 정상 판정으로 숨기지 않는다. 계산 원천에는 사용하지 않는다. */
public final class CapDisplaySql {
    private CapDisplaySql() { }

    /** cc 판정에 연결된 해결 건만 제외한다. 같은 계약의 다른 판정은 그대로 둔다. */
    public static final String RESOLVED_VIOLATION = """
            (EXISTS (SELECT 1 FROM fgc.exception_case resolved_exception
                     WHERE resolved_exception.exception_type = 'CAP_VIOLATION'
                       AND resolved_exception.status = 'RESOLVED'
                       AND (resolved_exception.cap_check_id = cc.cap_check_id
                            OR (resolved_exception.source_entity_type = 'CAP_CHECK'
                                AND resolved_exception.source_entity_id = cc.cap_check_id::text)
                            OR EXISTS (SELECT 1 FROM fgc.exception_occurrence resolved_occurrence
                                        WHERE resolved_occurrence.exception_case_id = resolved_exception.exception_case_id
                                          AND resolved_occurrence.validation_run_id = cc.validation_run_id
                                          AND ((resolved_occurrence.source_entity_type = 'CAP_CHECK'
                                                AND resolved_occurrence.source_entity_id = cc.cap_check_id::text)
                                               OR resolved_occurrence.evidence_snapshot ->> 'monthlyCapCheckId'
                                                  = cc.cap_check_id::text)))))
            """;

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
                            AND display_exception.status IN ('NEW', 'REVIEW_REQUIRED', 'IN_REVIEW')
                            AND COALESCE(display_exception.validation_month,
                                         CAST(date_trunc('month', cc.as_of_date) AS date))
                                = CAST(date_trunc('month', cc.as_of_date) AS date)))
            """;

    /** 이번 월 검증 대상에 포함된 계약·지급단계의 미해결 지급 사전확정 위반. */
    public static final String MONTHLY_PRE_CONFIRM_VIOLATIONS = """
            SELECT cc.* FROM fgc.cap_check cc
             WHERE cc.check_kind = 'PRE_CONFIRM'
               AND """ + OPEN_VIOLATION + """
               AND EXISTS (
                   SELECT 1 FROM fgc.validation_run vr
                   JOIN fgc.validation_target vt ON vt.validation_run_id = vr.validation_run_id
                   JOIN fgc.cap_check monthly ON monthly.validation_run_id = vr.validation_run_id
                        AND monthly.contract_id = vt.contract_id AND monthly.payment_stage = cc.payment_stage
                   WHERE vr.validation_run_id = :validationRunId
                     AND vt.contract_id = cc.contract_id AND vt.selection_status = 'SELECTED'
                     AND cc.as_of_date >= vr.validation_month
                     AND cc.as_of_date < vr.validation_month + INTERVAL '1 month'
               )
            """;

    /** 정상 월 판정으로 미해결 지급 위반을 가리지 않고 계약·지급단계별 한 번만 센다. */
    public static final String MONTHLY_DISPLAY_CHECKS = """
            (SELECT DISTINCT ON (cc.contract_id, cc.payment_stage) cc.*
               FROM (SELECT * FROM fgc.cap_check WHERE validation_run_id = :validationRunId
                     UNION ALL
            """ + MONTHLY_PRE_CONFIRM_VIOLATIONS + """
                    ) cc
              ORDER BY cc.contract_id, cc.payment_stage,
                       (cc.result_status = 'VIOLATION' AND NOT """ + RESOLVED_VIOLATION + """
                       ) DESC,
                       (cc.validation_run_id = :validationRunId) DESC NULLS LAST,
                       cc.checked_at DESC, cc.cap_check_id DESC)
            """;
}
