-- PER-007 재실행 멱등 조회의 실행 계획. 실제 행에서 키를 골라 유니크 인덱스를 타는지 본다(#673 시나리오 3).
-- 절 제목의 첫 단어가 기대 인덱스다. common.sh의 per007_explain_verdict가 읽는다.
SET search_path = fgc, public;
ANALYZE;

SELECT contract_id, payment_stage FROM schedule_header
 WHERE active_yn AND schedule_purpose = 'OPERATIONAL' ORDER BY schedule_header_id DESC LIMIT 1 \gset s_
\echo == uq_schedule_header_active_operational 스케줄 활성 운영본 ==
EXPLAIN (ANALYZE, BUFFERS) SELECT schedule_header_id FROM schedule_header
 WHERE contract_id = :s_contract_id AND payment_stage = :'s_payment_stage' AND active_yn = true AND schedule_purpose = 'OPERATIONAL';

SELECT schedule_header_id, commission_item_id, installment_no, COALESCE(beneficiary_agent_id, 0) AS agent_id FROM schedule_line
 ORDER BY schedule_line_id DESC LIMIT 1 \gset l_
\echo == uq_schedule_line_business 스케줄 회차 업무키 ==
EXPLAIN (ANALYZE, BUFFERS) SELECT schedule_line_id FROM schedule_line
 WHERE schedule_header_id = :l_schedule_header_id AND commission_item_id = :l_commission_item_id
   AND installment_no = :l_installment_no AND COALESCE(beneficiary_agent_id, 0) = :l_agent_id;

SELECT journal_type, source_entity_type, source_entity_id, revision_no FROM journal_header
 ORDER BY journal_header_id DESC LIMIT 1 \gset j_
\echo == uq_journal_source_revision 분개 원천키 JournalHeaderRepository.findBySourceKey ==
EXPLAIN (ANALYZE, BUFFERS) SELECT journal_header_id FROM journal_header
 WHERE journal_type = :'j_journal_type' AND source_entity_type = :'j_source_entity_type'
   AND source_entity_id = :'j_source_entity_id' AND revision_no = :j_revision_no;

\echo == uq_journal_current_posted_source 현재 기표 존재 확인 JournalHeaderRepository.existsBy... ==
EXPLAIN (ANALYZE, BUFFERS) SELECT 1 FROM journal_header
 WHERE journal_type = :'j_journal_type' AND source_entity_type = :'j_source_entity_type'
   AND source_entity_id = :'j_source_entity_id' AND status = 'POSTED' AND journal_type <> 'REVERSAL' LIMIT 1;

SELECT source_type, source_business_key FROM commission_transaction ORDER BY commission_transaction_id DESC LIMIT 1 \gset t_
\echo == uq_commission_transaction_source 지급 건 원천키 ==
EXPLAIN (ANALYZE, BUFFERS) SELECT commission_transaction_id FROM commission_transaction
 WHERE source_type = :'t_source_type' AND source_business_key = :'t_source_business_key';

\echo == uq_commission_transaction_confirm_idempotency 지급 확정 멱등키 ==
EXPLAIN (ANALYZE, BUFFERS) SELECT commission_transaction_id FROM commission_transaction
 WHERE confirm_idempotency_key = 'per007-explain';

SELECT validation_run_id, contract_id, payment_stage FROM cap_check ORDER BY cap_check_id DESC LIMIT 1 \gset c_
\echo == uq_cap_check_monthly 1,200% 판정 UPSERT 키 ==
EXPLAIN (ANALYZE, BUFFERS) SELECT cap_check_id FROM cap_check
 WHERE validation_run_id = :c_validation_run_id AND contract_id = :c_contract_id AND payment_stage = :'c_payment_stage';

SELECT validation_run_id, contract_id, payment_stage, as_of_date FROM arbitrage_check ORDER BY arbitrage_check_id DESC LIMIT 1 \gset a_
\echo == uq_arbitrage_check 차익거래 UPSERT 키 ==
EXPLAIN (ANALYZE, BUFFERS) SELECT arbitrage_check_id FROM arbitrage_check
 WHERE validation_run_id = :a_validation_run_id AND contract_id = :a_contract_id
   AND payment_stage = :'a_payment_stage' AND as_of_date = :'a_as_of_date';

SELECT reconciliation_run_id, match_group_key FROM reconciliation_result ORDER BY reconciliation_result_id DESC LIMIT 1 \gset r_
\echo == uq_reconciliation_result 대사 결과 키 ==
EXPLAIN (ANALYZE, BUFFERS) SELECT reconciliation_result_id FROM reconciliation_result
 WHERE reconciliation_run_id = :r_reconciliation_run_id AND match_group_key = :'r_match_group_key';

SELECT exception_key FROM exception_case ORDER BY exception_case_id DESC LIMIT 1 \gset e_
\echo == uq_exception_key 예외 업무키 ==
EXPLAIN (ANALYZE, BUFFERS) SELECT exception_case_id FROM exception_case WHERE exception_key = :'e_exception_key';
