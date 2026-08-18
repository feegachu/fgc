-- FGC-FUN-043 결과 집계 성능 — journal_header/reconciliation_run은 validation_run_id로
-- 조회하는데 인덱스가 없어 실행 계획이 매번 seq scan이었다(EXPLAIN으로 확인).
-- cap_check/arbitrage_check/validation_target은 이미 (validation_run_id, ...) 복합
-- UNIQUE 제약이 있어 그 인덱스를 그대로 쓸 수 있으니 대상에서 뺐다.
CREATE INDEX idx_journal_header_validation_run ON fgc.journal_header (validation_run_id);
CREATE INDEX idx_reconciliation_run_validation_run ON fgc.reconciliation_run (validation_run_id);
