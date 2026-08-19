-- SRC-032 D-03: 업무적으로 다른 한도 규칙과 대사 불일치 종류를 안정 업무키에서 구분한다.

UPDATE fgc.exception_case ec
   SET exception_key = CONCAT(
           ec.exception_type, ':', TO_CHAR(ec.validation_month, 'YYYY-MM'),
           ':CONTRACT:', ec.contract_id, ':', cc.payment_stage, ':', cc.cap_rule_set_id
       )
  FROM fgc.cap_check cc
 WHERE ec.source_entity_type = 'CAP_CHECK'
   AND ec.source_entity_id = CAST(cc.cap_check_id AS varchar);

UPDATE fgc.exception_case ec
   SET exception_key = CONCAT(
           'RECONCILIATION_MISMATCH:', TO_CHAR(ec.validation_month, 'YYYY-MM'),
           ':CONTRACT:', ec.contract_id, ':', reconciliation.payment_stage, ':',
           LEFT(rr.match_group_key, 140), ':', MD5(rr.match_group_key), ':',
           COALESCE(rr.primary_reason_code, rr.result_type, 'RECONCILIATION_MISMATCH')
       )
  FROM fgc.reconciliation_result rr
  JOIN fgc.reconciliation_run reconciliation
    ON reconciliation.reconciliation_run_id = rr.reconciliation_run_id
 WHERE ec.source_entity_type = 'RECONCILIATION_RESULT'
   AND ec.source_entity_id = CAST(rr.reconciliation_result_id AS varchar);
