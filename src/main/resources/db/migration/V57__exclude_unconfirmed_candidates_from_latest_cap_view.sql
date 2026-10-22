-- #606: 계약 목록도 상세와 같이 확정되지 않은 후보의 판정을 현재 판정에서 제외한다.
-- 원본 검증·검출 이력은 보존하며 예외의 처리상태만으로 실제 판정을 바꾸지 않는다.
CREATE OR REPLACE VIEW fgc.vw_latest_cap_check AS
SELECT DISTINCT ON (cc.contract_id, cc.payment_stage)
       cc.cap_check_id, cc.contract_id, cc.payment_stage, cc.as_of_date,
       cc.limit_amount, cc.included_amount, cc.remaining_amount, cc.usage_pct, cc.result_status,
       cc.validation_run_id
  FROM fgc.cap_check cc
 WHERE (
         cc.candidate_transaction_id IS NULL
         AND NOT jsonb_exists(cc.calculation_snapshot, 'deletedCandidateTransactionId')
       )
    OR EXISTS (
         SELECT 1
           FROM fgc.commission_transaction candidate
          WHERE candidate.commission_transaction_id = cc.candidate_transaction_id
            AND candidate.status = 'CONFIRMED'
       )
 ORDER BY cc.contract_id, cc.payment_stage, cc.checked_at DESC, cc.cap_check_id DESC;

COMMENT ON VIEW fgc.vw_latest_cap_check IS
    '계약·지급단계별 최신 유효 1,200% 판정. 미확정·취소·삭제 후보의 판정은 제외하고 검출 근거는 보존';
