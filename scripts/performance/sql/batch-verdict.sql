-- 첫 번째 월 통합검증의 스케줄 행|처리 계약|초|상태. PER-003 판정에 쓴다.
SELECT (SELECT count(*) FROM fgc.schedule_line),
       (SELECT count(*) FROM fgc.validation_target
         WHERE validation_run_id = (SELECT min(validation_run_id) FROM fgc.validation_run)),
       ceil(extract(epoch FROM e.end_time - e.start_time)), -- 600.4초가 600으로 합격하지 않게 올린다
       e.status
  FROM fgc.batch_job_execution e
 ORDER BY e.job_execution_id
 LIMIT 1;
