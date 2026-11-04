-- 배치 시간, 처리 행 수, DB 병목. pg_stat_statements에는 적재와 검증 조회도 함께 들어 있다.
SET search_path = fgc, public;
\pset footer off

\echo == 전체 ==
SELECT e.job_execution_id AS job, i.job_name, e.status,
       round(extract(epoch FROM e.end_time - e.start_time)::numeric, 1) AS seconds
  FROM batch_job_execution e JOIN batch_job_instance i USING (job_instance_id)
 ORDER BY 1;

\echo == Step별 ==
SELECT s.job_execution_id AS job, s.step_name, s.status, s.read_count, s.write_count, s.process_skip_count AS skip,
       round(extract(epoch FROM s.end_time - s.start_time)::numeric, 1) AS seconds
  FROM batch_step_execution s
 ORDER BY s.step_execution_id;

\echo == 처리 행 수 ==
SELECT 'insurance_contract' AS tbl, count(*) AS rows FROM insurance_contract
UNION ALL SELECT 'validation_target', count(*) FROM validation_target
UNION ALL SELECT 'schedule_header', count(*) FROM schedule_header
UNION ALL SELECT 'schedule_line', count(*) FROM schedule_line
UNION ALL SELECT 'cap_check', count(*) FROM cap_check
UNION ALL SELECT 'arbitrage_check', count(*) FROM arbitrage_check
UNION ALL SELECT 'journal_header', count(*) FROM journal_header
UNION ALL SELECT 'journal_line', count(*) FROM journal_line
UNION ALL SELECT 'reconciliation_result', count(*) FROM reconciliation_result
UNION ALL SELECT 'exception_case', count(*) FROM exception_case;

\echo == pg_stat_statements 상위 15 (총 시간) ==
SELECT round((total_exec_time / 1000)::numeric, 1) AS total_s, calls, round(mean_exec_time::numeric, 2) AS mean_ms,
       rows, shared_blks_read AS blks_read, left(regexp_replace(query, '\s+', ' ', 'g'), 120) AS query
  FROM pg_stat_statements
 ORDER BY total_exec_time DESC
 LIMIT 15;

\echo == SQL 합계 (전체 시간과 비교한다) ==
SELECT count(*) AS statements, sum(calls) AS calls, round((sum(total_exec_time) / 1000)::numeric, 1) AS total_s
  FROM pg_stat_statements;

\echo == 블록을 많이 읽는 SQL 상위 5 (건별 순차 스캔 찾기) ==
SELECT shared_blks_hit AS blks_hit, calls, round((total_exec_time / 1000)::numeric, 1) AS total_s,
       left(regexp_replace(query, '\s+', ' ', 'g'), 120) AS query
  FROM pg_stat_statements
 ORDER BY shared_blks_hit DESC
 LIMIT 5;

\echo == 커밋, WAL (DB 생성부터 누계) ==
SELECT d.xact_commit, d.xact_rollback, d.tup_inserted, d.tup_updated, w.wal_records, pg_size_pretty(w.wal_bytes) AS wal,
       w.wal_write, w.wal_sync, round((w.wal_sync_time / 1000)::numeric, 1) AS wal_sync_s
  FROM pg_stat_database d CROSS JOIN pg_stat_wal w
 WHERE d.datname = current_database();

\echo == 캐시 적중률, 임시 파일 ==
SELECT round(100.0 * blks_hit / nullif(blks_hit + blks_read, 0), 2) AS hit_pct, blks_read, temp_files,
       pg_size_pretty(temp_bytes) AS temp, deadlocks, pg_size_pretty(pg_database_size(datname)) AS db_size
  FROM pg_stat_database
 WHERE datname = current_database();

\echo == 순차 스캔 상위 5 ==
SELECT relname, seq_scan, seq_tup_read, idx_scan, n_live_tup
  FROM pg_stat_user_tables
 ORDER BY seq_tup_read DESC
 LIMIT 5;
